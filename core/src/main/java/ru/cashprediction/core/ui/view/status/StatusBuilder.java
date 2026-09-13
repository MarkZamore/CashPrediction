package ru.cashprediction.core.ui.view.status;

import java.time.Instant;
import ru.cashprediction.core.app.AppState;

/**
 * Построение строки состояния из состояния приложения (спецификация v2, §5.4, тексты §8.1).
 *
 * <p><b>Сегменты:</b></p>
 * <ol>
 *   <li>file: {@code status.file} «Файл: {имя.md}» (подсказка — полный путь) или {@code status.file.none}
 *       (подсказка {@code status.file.tip.none});</li>
 *   <li>dirty: {@code status.dirty} ({@code warn}), {@code status.saved} или {@code status.clean} ({@code text.muted});</li>
 *   <li>rows: {@code status.rows} — только строки событий (без итогов и PAST_HEADER);</li>
 *   <li>horizon: {@code status.horizon} «Горизонт: {horizonLabel} (до dd.MM.yyyy)», для UNTIL {@code status.horizon.until};</li>
 *   <li>message: {@code StatusMessages.visible(now)}, цвет по {@link StatusLevel}, растягивается;</li>
 *   <li>whatIf (только когда активно): {@code status.whatIf}, цвет {@code whatif}, подсказка {@code status.whatIf.tip};</li>
 *   <li>autosave (только когда включено): {@code status.autosave} или {@code status.autosave.problem} ({@code warn},
 *       подсказка — текст проблемы);</li>
 *   <li>session: {@code status.session.none} до первой записи; {@code status.session} со списком
 *       {@code status.session.store.ok}/{@code .fail} через « | » (сбой — цвет {@code expense}); web — одно хранилище
 *       {@code store.server}; второй экземпляр {@code status.session.disabled}; ожидание
 *       {@code status.session.pending}; подсказки {@code status.session.tip.ok}/{@code .fail} по строке на
 *       хранилище.</li>
 * </ol>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class StatusBuilder {

    private StatusBuilder() {
    }

    /**
     * Строит строку состояния.
     *
     * @param state состояние приложения
     * @param now   текущий момент (для истечения сообщений и времени записи)
     * @return модель строки состояния
     */
    public static StatusModel build(AppState state, Instant now) {
        throw new UnsupportedOperationException("S1: core-menu — StatusBuilder.build");
    }
}
