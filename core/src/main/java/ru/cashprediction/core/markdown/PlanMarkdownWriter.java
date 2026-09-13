package ru.cashprediction.core.markdown;

import java.util.ArrayList;
import java.util.List;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RawBlock;
import ru.cashprediction.core.model.RecurringRule;

/**
 * Писатель файла плана: превращает {@link Plan} в канонический Markdown.
 *
 * <p>Правила вывода:</p>
 * <ul>
 *   <li>UTF-8 без BOM (кодировку задаёт вызывающий), переводы строк {@code \n}, ровно одна пустая строка
 *       между блоками и ровно один перевод строки в конце файла;</li>
 *   <li>всегда выводятся заголовок {@code # План: имя}, секция «Параметры» (Формат, Валюта, Начало, Горизонт,
 *       Начальный баланс) и секция «Регулярные операции» (с заголовком таблицы даже без строк);</li>
 *   <li>«Подушка безопасности» — только если не ноль; «Цель» и «Название цели» — только если цель задана
 *       (название — если оно не пустое), «Цель к дате» — только если задана дата;</li>
 *   <li>«Заметка», «Разовые операции», «Корректировки» — только если не пусты или если к секции привязан
 *       нераспознанный фрагмент (тогда пустая секция выводится одним заголовком, без таблицы);</li>
 *   <li>нераспознанные при чтении фрагменты ({@link RawBlock}) выводятся после секции, за которой стояли;
 *       неизвестные параметры — внутри списка «Параметры».</li>
 * </ul>
 *
 * <p>В файл не пишется ничего служебного (комментариев, меток времени), чтобы файл оставался чистым
 * человеческим документом и одинаковый план давал одинаковый текст байт в байт во всех трёх клиентах.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class PlanMarkdownWriter {

    private PlanMarkdownWriter() {
    }

    /**
     * Выводит план в Markdown.
     *
     * @param plan план
     * @return полный текст файла
     */
    public static String write(Plan plan) {
        List<List<String>> blocks = new ArrayList<>();
        blocks.add(List.of(MarkdownFormat.TITLE_PREFIX + singleLine(plan.name())));
        addRawBlocks(blocks, plan, "");

        blocks.add(heading(MarkdownFormat.SECTION_PARAMETERS));
        blocks.add(parameterLines(plan));
        addRawBlocks(blocks, plan, MarkdownFormat.SECTION_PARAMETERS);

        // Заголовок необязательной секции пишется и тогда, когда она пуста, но к ней привязан нераспознанный
        // фрагмент: без заголовка текст «переехал» бы под предыдущую секцию и при следующем чтении привязался бы к ней.
        List<String> note = noteLines(plan.note());
        if (!note.isEmpty() || hasRawBlocks(plan, MarkdownFormat.SECTION_NOTE)) {
            blocks.add(heading(MarkdownFormat.SECTION_NOTE));
            if (!note.isEmpty()) {
                blocks.add(note);
            }
        }
        addRawBlocks(blocks, plan, MarkdownFormat.SECTION_NOTE);

        blocks.add(heading(MarkdownFormat.SECTION_RULES));
        blocks.add(MarkdownTable.format(MarkdownFormat.RULE_COLUMNS, plan.rules().stream().map(PlanMarkdownWriter::ruleCells).toList()));
        addRawBlocks(blocks, plan, MarkdownFormat.SECTION_RULES);

        if (!plan.oneTimes().isEmpty()) {
            blocks.add(heading(MarkdownFormat.SECTION_ONE_TIME));
            blocks.add(MarkdownTable.format(MarkdownFormat.ONE_TIME_COLUMNS,
                    plan.oneTimes().stream().map(PlanMarkdownWriter::oneTimeCells).toList()));
        } else if (hasRawBlocks(plan, MarkdownFormat.SECTION_ONE_TIME)) {
            blocks.add(heading(MarkdownFormat.SECTION_ONE_TIME));
        }
        addRawBlocks(blocks, plan, MarkdownFormat.SECTION_ONE_TIME);

        if (!plan.adjustments().isEmpty()) {
            blocks.add(heading(MarkdownFormat.SECTION_ADJUSTMENTS));
            blocks.add(MarkdownTable.format(MarkdownFormat.ADJUSTMENT_COLUMNS,
                    plan.adjustments().stream().map(PlanMarkdownWriter::adjustmentCells).toList()));
        } else if (hasRawBlocks(plan, MarkdownFormat.SECTION_ADJUSTMENTS)) {
            blocks.add(heading(MarkdownFormat.SECTION_ADJUSTMENTS));
        }
        addRawBlocks(blocks, plan, MarkdownFormat.SECTION_ADJUSTMENTS);

        // Фрагменты с незнакомой привязкой (например, из снимка другой версии) не теряются: в конец файла.
        for (RawBlock block : plan.rawBlocks()) {
            if (!isKnownAnchor(block.afterSection())) {
                addBlock(blocks, block.lines());
            }
        }

        StringBuilder sb = new StringBuilder();
        for (List<String> block : blocks) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            for (String line : block) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<String> heading(String title) {
        return List.of(MarkdownFormat.SECTION_PREFIX + title);
    }

    private static List<String> parameterLines(Plan plan) {
        List<String> lines = new ArrayList<>();
        lines.add(item(MarkdownFormat.KEY_FORMAT, MarkdownFormat.formatValue()));
        lines.add(item(MarkdownFormat.KEY_CURRENCY, plan.currency()));
        lines.add(item(MarkdownFormat.KEY_START, RuFormats.formatDate(plan.startDate())));
        lines.add(item(MarkdownFormat.KEY_HORIZON, RuFormats.formatHorizon(plan.horizon())));
        lines.add(item(MarkdownFormat.KEY_START_BALANCE, RuFormats.formatMoney(plan.startBalance())));
        if (!plan.cushion().isZero()) {
            lines.add(item(MarkdownFormat.KEY_CUSHION, RuFormats.formatMoney(plan.cushion())));
        }
        Goal goal = plan.goal();
        if (goal != null) {
            lines.add(item(MarkdownFormat.KEY_GOAL, RuFormats.formatMoney(goal.target())));
            if (goal.wishDate() != null) {
                lines.add(item(MarkdownFormat.KEY_GOAL_DATE, RuFormats.formatDate(goal.wishDate())));
            }
            if (!goal.title().isBlank()) {
                lines.add(item(MarkdownFormat.KEY_GOAL_TITLE, goal.title()));
            }
        }
        for (RawBlock block : plan.rawBlocks()) {
            if (block.afterSection().equals(MarkdownFormat.PARAMETER_EXTRAS_ANCHOR)) {
                block.lines().stream().map(String::strip).filter(l -> !l.isEmpty()).forEach(lines::add);
            }
        }
        return lines;
    }

    private static String item(String key, String value) {
        return new ListItem(key, singleLine(value)).format();
    }

    private static List<String> noteLines(String note) {
        List<String> lines = new ArrayList<>();
        for (String line : note.split("\\r\\n|\\r|\\n", -1)) {
            lines.add(MarkdownFormat.escapeNoteLine(line.stripTrailing()));
        }
        return trimBlankLines(lines);
    }

    private static List<String> ruleCells(RecurringRule rule) {
        return List.of(
                rule.id().value(),
                rule.title(),
                RuFormats.formatKind(rule.kind()),
                RuFormats.formatMoney(rule.amount()),
                rule.category(),
                RuFormats.formatRecurrence(rule.recurrence()),
                RuFormats.formatDate(rule.from()),
                RuFormats.formatDate(rule.until()),
                RuFormats.formatWeekendPolicy(rule.weekendPolicy()),
                RuFormats.formatBoolean(rule.enabled()),
                rule.note());
    }

    private static List<String> oneTimeCells(OneTimeTransaction tx) {
        return List.of(
                tx.id().value(),
                RuFormats.formatDate(tx.date()),
                tx.title(),
                RuFormats.formatKind(tx.kind()),
                RuFormats.formatMoney(tx.amount()),
                tx.category(),
                tx.note());
    }

    private static List<String> adjustmentCells(Adjustment adjustment) {
        Adjustment.Action action = adjustment.action();
        return List.of(
                adjustment.key().ruleId().value(),
                RuFormats.formatDate(adjustment.key().originalDate()),
                RuFormats.formatAction(action),
                action.newAmount().map(RuFormats::formatMoney).orElse(""),
                action.newDate().map(RuFormats::formatDate).orElse(""),
                adjustment.note());
    }

    private static void addRawBlocks(List<List<String>> blocks, Plan plan, String anchor) {
        for (RawBlock block : plan.rawBlocks()) {
            if (RuFormats.normalize(block.afterSection()).equals(RuFormats.normalize(anchor))) {
                addBlock(blocks, block.lines());
            }
        }
    }

    /** @return есть ли непустой нераспознанный фрагмент, привязанный к секции {@code anchor} */
    private static boolean hasRawBlocks(Plan plan, String anchor) {
        String normalized = RuFormats.normalize(anchor);
        return plan.rawBlocks().stream().anyMatch(block -> RuFormats.normalize(block.afterSection()).equals(normalized)
                && block.lines().stream().anyMatch(line -> !line.isBlank()));
    }

    private static void addBlock(List<List<String>> blocks, List<String> lines) {
        List<String> cleaned = trimBlankLines(lines.stream().map(String::stripTrailing).toList());
        if (!cleaned.isEmpty()) {
            blocks.add(cleaned);
        }
    }

    private static boolean isKnownAnchor(String anchor) {
        if (anchor.isEmpty() || anchor.equals(MarkdownFormat.PARAMETER_EXTRAS_ANCHOR)) {
            return true;
        }
        String normalized = RuFormats.normalize(anchor);
        return MarkdownFormat.SECTION_ORDER.stream().anyMatch(s -> RuFormats.normalize(s).equals(normalized));
    }

    /** Значение параметра или заголовка обязано помещаться в одну строку, иначе файл перестанет читаться. */
    private static String singleLine(String value) {
        return value == null ? "" : value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static List<String> trimBlankLines(List<String> source) {
        int from = 0;
        int to = source.size();
        while (from < to && source.get(from).isBlank()) {
            from++;
        }
        while (to > from && source.get(to - 1).isBlank()) {
            to--;
        }
        return new ArrayList<>(source.subList(from, to));
    }
}
