package ru.cashprediction.core.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

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

    /** Целая часть и дробь из цифр ASCII: после очистки строки от пробелов и знака. */
    private static final Pattern DIGITS = Pattern.compile("[0-9]*");

    /**
     * Компактный конструктор: запрещает {@link Long#MIN_VALUE}, у которого нет положительной пары,
     * иначе {@link #abs()} и форматирование переполнились бы.
     */
    public Money {
        if (minor == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Сумма вне допустимого диапазона");
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
     * <p>Правила терпимы к ручному вводу:</p>
     * <ul>
     *   <li>любые пробелы (включая неразрывные U+00A0 и U+202F), апостроф и символы валют (₽, $, €) игнорируются;</li>
     *   <li>знак: ведущий {@code +}, {@code -} или типографский минус {@code −};</li>
     *   <li>десятичный разделитель: запятая или точка; если есть оба, разделителем считается последний,
     *       а другой символ считается разделителем тысяч ({@code 1.234,56} и {@code 1,234.56});</li>
     *   <li>если один и тот же символ встречается несколько раз, он считается разделителем тысяч ({@code 1,234,567});</li>
     *   <li>больше двух знаков после запятой округляются до копейки по правилу HALF_UP.</li>
     * </ul>
     *
     * @param text исходный текст, например {@code "80 000,00"}, {@code "80000.5"}, {@code "-1 200"}
     * @return разобранная сумма
     * @throws IllegalArgumentException если текст пуст или не является числом
     */
    public static Money parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Сумма не указана");
        }
        // Шаг 1: выбрасываем всё «декоративное» и нормализуем типографский минус.
        StringBuilder cleaned = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == '\''
                    || Character.getType(cp) == Character.CURRENCY_SYMBOL) {
                return;
            }
            cleaned.appendCodePoint(cp == '−' ? '-' : cp);
        });
        String s = cleaned.toString();
        boolean negative = false;
        if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Сумма не указана");
        }

        // Шаг 2: определяем десятичный разделитель.
        String integerPart;
        String fractionPart;
        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            int decimal = Math.max(lastComma, lastDot);
            String grouping = decimal == lastComma ? "." : ",";
            integerPart = s.substring(0, decimal).replace(grouping, "");
            fractionPart = s.substring(decimal + 1);
        } else {
            String separator = lastComma >= 0 ? "," : ".";
            int first = s.indexOf(separator);
            int last = s.lastIndexOf(separator);
            if (last < 0) {
                integerPart = s;
                fractionPart = "";
            } else if (first != last) {
                integerPart = s.replace(separator, "");
                fractionPart = "";
            } else {
                integerPart = s.substring(0, last);
                fractionPart = s.substring(last + 1);
            }
        }
        if (integerPart.isEmpty() && fractionPart.isEmpty()) {
            throw new IllegalArgumentException("Некорректная сумма: «" + text.strip() + "»");
        }
        if (!DIGITS.matcher(integerPart).matches() || !DIGITS.matcher(fractionPart).matches()) {
            throw new IllegalArgumentException("Некорректная сумма: «" + text.strip() + "»");
        }

        // Шаг 3: переводим в копейки с округлением.
        try {
            BigDecimal value = new BigDecimal((integerPart.isEmpty() ? "0" : integerPart)
                    + "." + (fractionPart.isEmpty() ? "0" : fractionPart));
            long minor = value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
            return new Money(negative ? -minor : minor);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Слишком большая сумма: «" + text.strip() + "»", e);
        }
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
            throw new IllegalArgumentException("Делитель должен быть положительным");
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
