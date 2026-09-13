package ru.cashprediction.core.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import ru.cashprediction.core.text.Texts;

/**
 * Денежная сумма в минимальных единицах (копейках, центах).
 *
 * <p>Почему {@code long}, а не {@code BigDecimal}: прогнозу нужны только сложение и вычитание,
 * изредка умножение на коэффициент «что-если». Целые копейки исключают ошибки округления
 * при нарастающем итоге и позволяют хранить ежедневный баланс массивом {@code long[]} без аллокаций.</p>
 *
 * <p>Формат вывода фиксирован и не зависит от локали JVM: {@code 80 000,00} (пробел между тысячами,
 * запятая перед копейками). Тот же текст пишется в файлы CashMemory, поэтому файлы одинаковы
 * на любой машине.</p>
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param minor сумма в минимальных единицах; отрицательная означает долг или расход
 */
public record Money(long minor) implements Comparable<Money> {

    /** Нулевая сумма. */
    public static final Money ZERO = new Money(0);

    /** Дробная часть из цифр ASCII (разделители разрядов в ней недопустимы). */
    private static final Pattern DIGITS = Pattern.compile("[0-9]*");

    /**
     * Компактный конструктор: запрещает {@link Long#MIN_VALUE}, у которого нет положительной пары,
     * иначе {@link #abs()} и форматирование переполнились бы.
     */
    public Money {
        if (minor == Long.MIN_VALUE) {
            throw new IllegalArgumentException(Texts.get("money.error.outOfRange"));
        }
    }

    /**
     * Создаёт сумму из целых единиц (рублей).
     *
     * @param major количество целых единиц
     * @return сумма {@code major * 100} копеек
     */
    public static Money ofMajor(long major) {
        return new Money(Math.multiplyExact(major, 100L));
    }

    /**
     * Создаёт сумму из минимальных единиц.
     *
     * @param minor количество копеек
     * @return сумма
     */
    public static Money ofMinor(long minor) {
        return new Money(minor);
    }

    /**
     * Разбирает сумму, введённую человеком или записанную в .md-файл.
     *
     * <p>Правила терпимы к привычным формам записи, но строги к разделителям разрядов:</p>
     * <ul>
     *   <li>символы валют (₽, $, €) и пробелы по краям игнорируются;</li>
     *   <li>знак: ведущий {@code +}, {@code -} или типографский минус {@code −};</li>
     *   <li>десятичный разделитель: запятая или точка; если есть оба, разделителем считается последний,
     *       а другой символ считается разделителем разрядов ({@code 1.234,56} и {@code 1,234.56});</li>
     *   <li>если один и тот же знак препинания встречается несколько раз, он считается разделителем разрядов
     *       ({@code 1,234,567}); одиночная запятая или точка — десятичный разделитель;</li>
     *   <li>пробелы внутри числа (включая неразрывные U+00A0 и U+202F) и апостроф — тоже разделители разрядов;</li>
     *   <li><b>группы разрядов строгие:</b> если в целой части есть разделитель разрядов, первая группа содержит
     *       от 1 до 3 цифр, а каждая следующая — ровно 3 ({@code 1 234 567}); запись {@code 12 3}, {@code 1 23},
     *       {@code 12,3,4} или {@code 1,23,456} отклоняется: такая опечатка иначе молча превратилась бы
     *       в другую сумму;</li>
     *   <li>больше двух знаков после запятой округляются до копейки по правилу HALF_UP.</li>
     * </ul>
     *
     * @param text исходный текст, например {@code "80 000,00"}, {@code "80000.5"}, {@code "-1 200"}
     * @return разобранная сумма
     * @throws IllegalArgumentException если текст пуст, не является числом или разряды сгруппированы неверно
     *                                  (сообщение по-русски, начинается с «Некорректная сумма: «…»»)
     */
    public static Money parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException(Texts.get("money.error.empty"));
        }
        // Шаг 1: выбрасываем символы валют и нормализуем типографский минус; пробелы пока сохраняем,
        // потому что внутри числа они разделяют разряды и участвуют в строгой проверке групп.
        StringBuilder cleaned = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.CURRENCY_SYMBOL) {
                return;
            }
            cleaned.appendCodePoint(cp == '−' ? '-' : isBlank(cp) ? ' ' : cp);
        });
        String s = cleaned.toString().strip();
        boolean negative = false;
        if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1).strip();
        } else if (s.startsWith("+")) {
            s = s.substring(1).strip();
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException(Texts.get("money.error.empty"));
        }

        // Шаг 2: определяем десятичный разделитель и знак препинания, разделяющий разряды.
        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');
        int decimal;
        char groupingPunct;
        if (lastComma >= 0 && lastDot >= 0) {
            decimal = Math.max(lastComma, lastDot);
            groupingPunct = decimal == lastComma ? '.' : ',';
        } else if (lastComma >= 0 || lastDot >= 0) {
            char separator = lastComma >= 0 ? ',' : '.';
            boolean repeated = s.indexOf(separator) != s.lastIndexOf(separator);
            // Повторяющийся знак — разделитель разрядов («1,234,567»), одиночный — десятичный («80000.5»).
            decimal = repeated ? -1 : s.lastIndexOf(separator);
            groupingPunct = repeated ? separator : 0;
        } else {
            decimal = -1;
            groupingPunct = 0;
        }
        String integerRaw = decimal < 0 ? s : s.substring(0, decimal);
        String fractionPart = decimal < 0 ? "" : s.substring(decimal + 1);
        String integerPart = integerDigits(integerRaw, groupingPunct, text);
        if (integerPart.isEmpty() && fractionPart.isEmpty()) {
            throw invalid(text);
        }
        if (!DIGITS.matcher(fractionPart).matches()) {
            throw invalid(text);
        }

        // Шаг 3: переводим в копейки с округлением.
        try {
            BigDecimal value = new BigDecimal((integerPart.isEmpty() ? "0" : integerPart)
                    + "." + (fractionPart.isEmpty() ? "0" : fractionPart));
            long minor = value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
            return new Money(negative ? -minor : minor);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(Texts.get("money.error.tooLarge", text.strip()), e);
        }
    }

    /**
     * Проверяет группы разрядов целой части и возвращает её цифры без разделителей.
     *
     * @param raw           целая часть до десятичного разделителя (пробелы уже приведены к обычному пробелу)
     * @param groupingPunct знак препинания, разделяющий разряды, или {@code 0}, если такого нет
     * @param original      исходный текст для сообщения об ошибке
     * @return только цифры целой части (может быть пустой строкой, например для «,5»)
     * @throws IllegalArgumentException если встретился посторонний символ или группа неверной длины
     */
    private static String integerDigits(String raw, char groupingPunct, String original) {
        List<String> groups = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean grouped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                current.append(c);
            } else if (c == ' ' || c == '\'' || (groupingPunct != 0 && c == groupingPunct)) {
                // Серия пробелов подряд считается одним разделителем: два пробела при вводе — не повод для ошибки.
                boolean spaceRun = c == ' ' && i > 0 && raw.charAt(i - 1) == ' ';
                if (!spaceRun) {
                    groups.add(current.toString());
                    current.setLength(0);
                    grouped = true;
                }
            } else {
                throw invalid(original);
            }
        }
        groups.add(current.toString());
        if (!grouped) {
            return groups.getFirst();
        }
        String first = groups.getFirst();
        if (first.isEmpty() || first.length() > 3) {
            throw invalidGrouping(original);
        }
        for (int i = 1; i < groups.size(); i++) {
            if (groups.get(i).length() != 3) {
                throw invalidGrouping(original);
            }
        }
        return String.join("", groups);
    }

    /** @return {@code true} для любого пробельного символа, включая неразрывные U+00A0 и U+202F */
    private static boolean isBlank(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static IllegalArgumentException invalid(String text) {
        return new IllegalArgumentException(Texts.get("money.error.invalid", text.strip()));
    }

    /**
     * Ошибка неверной группировки разрядов (решение L1).
     *
     * <p>Текст берётся из общего каталога ({@code money.error.grouping}), как и все сообщения {@code parse}: сообщения
     * модели не пишутся литералами (решение L13).</p>
     */
    private static IllegalArgumentException invalidGrouping(String text) {
        return new IllegalArgumentException(Texts.get("money.error.grouping", text.strip()));
    }

    /**
     * Сообщает, содержал ли текст больше двух знаков после десятичного разделителя
     * (то есть потребовалось округление). Используется для диагностики при чтении файлов.
     *
     * @param text исходный текст суммы
     * @return {@code true}, если при разборе сумма была округлена
     */
    public static boolean needsRounding(String text) {
        if (text == null) {
            return false;
        }
        String s = text.replaceAll("[\\s\\u00A0\\u202F']", "");
        int decimal = Math.max(s.lastIndexOf(','), s.lastIndexOf('.'));
        if (decimal < 0) {
            return false;
        }
        long digitsAfter = s.substring(decimal + 1).chars().filter(Character::isDigit).count();
        return digitsAfter > 2;
    }

    /** @return сумма {@code this + other} (переполнение приводит к {@link ArithmeticException}) */
    public Money plus(Money other) {
        return new Money(Math.addExact(minor, other.minor));
    }

    /** @return разность {@code this - other} */
    public Money minus(Money other) {
        return new Money(Math.subtractExact(minor, other.minor));
    }

    /** @return сумма с противоположным знаком */
    public Money negate() {
        return new Money(-minor);
    }

    /** @return модуль суммы */
    public Money abs() {
        return minor < 0 ? negate() : this;
    }

    /**
     * Умножает сумму на коэффициент с округлением до копейки (HALF_UP).
     *
     * @param factor коэффициент, например {@code 1.10} для «+10 %»
     * @return результат умножения
     */
    public Money times(BigDecimal factor) {
        BigDecimal result = BigDecimal.valueOf(minor).multiply(factor).setScale(0, RoundingMode.HALF_UP);
        return new Money(result.longValueExact());
    }

    /**
     * Делит сумму на целое число с округлением вверх до целого рубля.
     * Нужна калькулятору цели: «сколько откладывать в месяц» лучше округлить в большую сторону.
     *
     * @param divisor положительный делитель
     * @return частное, округлённое вверх до рубля
     */
    public Money divideCeilToMajor(long divisor) {
        if (divisor <= 0) {
            // Сообщение для разработчика: делитель передаёт код, а не пользователь.
            throw new IllegalArgumentException("Divisor must be positive: " + divisor);
        }
        BigDecimal rubles = BigDecimal.valueOf(minor)
                .divide(BigDecimal.valueOf(divisor * 100L), 0, RoundingMode.CEILING);
        return ofMajor(rubles.longValueExact());
    }

    /** @return {@code true}, если сумма меньше нуля */
    public boolean isNegative() {
        return minor < 0;
    }

    /** @return {@code true}, если сумма больше нуля */
    public boolean isPositive() {
        return minor > 0;
    }

    /** @return {@code true}, если сумма равна нулю */
    public boolean isZero() {
        return minor == 0;
    }

    /** @return -1, 0 или 1 в зависимости от знака */
    public int signum() {
        return Long.signum(minor);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(minor, other.minor);
    }

    /** @return {@code true}, если {@code this < other} */
    public boolean isLessThan(Money other) {
        return minor < other.minor;
    }

    /** @return меньшая из двух сумм */
    public static Money min(Money a, Money b) {
        return a.minor <= b.minor ? a : b;
    }

    /** @return большая из двух сумм */
    public static Money max(Money a, Money b) {
        return a.minor >= b.minor ? a : b;
    }

    /**
     * Форматирует сумму для людей и для файлов: {@code 80 000,00}, {@code -1 200,50}.
     * Разделитель тысяч: обычный пробел (парсер понимает любой).
     *
     * @return отформатированная сумма без валюты
     */
    public String format() {
        long abs = Math.abs(minor);
        String digits = Long.toString(abs / 100);
        StringBuilder sb = new StringBuilder(digits.length() + 8);
        if (minor < 0) {
            sb.append('-');
        }
        for (int i = 0; i < digits.length(); i++) {
            // Пробел перед каждой тройкой цифр, считая справа.
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        long cents = abs % 100;
        sb.append(',').append(cents < 10 ? "0" : "").append(cents);
        return sb.toString();
    }

    /**
     * Форматирует сумму с валютой: {@code 80 000,00 ₽}.
     *
     * @param currency обозначение валюты; пустое значение означает «без валюты»
     * @return отформатированная сумма
     */
    public String format(String currency) {
        return currency == null || currency.isBlank() ? format() : format() + " " + currency;
    }

    /**
     * Форматирует сумму со знаком: {@code +80 000,00} или {@code -45 000,00}; ноль без знака.
     *
     * @return сумма со знаком
     */
    public String formatSigned() {
        return minor > 0 ? "+" + format() : format();
    }

    /**
     * Каноническая форма для полей снимка сессии: без разделителей тысяч, {@code 95000,00}.
     * Однозначно читается {@link #parse(String)} и не зависит от локали.
     *
     * @return сумма без группировки разрядов
     */
    public String formatPlain() {
        long abs = Math.abs(minor);
        long cents = abs % 100;
        return (minor < 0 ? "-" : "") + (abs / 100) + "," + (cents < 10 ? "0" : "") + cents;
    }

    /** @return то же, что {@link #format()} */
    @Override
    public String toString() {
        return format();
    }
}
