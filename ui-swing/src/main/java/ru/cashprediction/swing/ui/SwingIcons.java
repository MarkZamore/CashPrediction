package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.font.GraphicAttribute;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.*;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicLabelUI;
import javax.swing.plaf.basic.BasicSpinnerUI;
import javax.swing.plaf.metal.MetalButtonUI;
import ru.cashprediction.core.ui.token.UiIcons;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.DesignTokens;

/** Единственный адаптер общих PNG ядра; клиент не хранит собственных изображений. */
public final class SwingIcons {
    private static final java.util.Map<String, ImageIcon> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<ImageIdentity, RetainedSource> SOURCES = new java.util.HashMap<>();
    private static final ReferenceQueue<Image> RELEASED_IMAGES = new ReferenceQueue<>();
    private static long sourceSequence;
    private SwingIcons() { }

    /** Неизменяемый снимок пикселей конкретного объекта; hints отсутствует у исходного decode. */
    record DecodedImage(String sourceObjectIdentity, int width, int height, String argbSha256, Integer scalingHints) { }

    /** Исходные PNG-байты и фактическая цепочка decode/scaling, без семантических маркеров виджета. */
    record DecodedPng(byte[] bytes, String source, List<DecodedImage> chain) {
        /** Сохраняет независимые байты и неизменяемую цепочку уже материализованных объектов. */
        DecodedPng { bytes = bytes.clone(); chain = List.copyOf(chain); }
        /** Возвращает копию исходного PNG, а не внутреннюю память реестра. */
        @Override public byte[] bytes() { return bytes.clone(); }
        /** Возвращает идентичность установленного объекта, последнего в цепочке. */
        public String sourceObjectIdentity() { return chain.getLast().sourceObjectIdentity(); }
    }

    /** Слабая ссылка не удерживает временные значки каждой перерисовки текста. */
    private record RetainedSource(DecodedPng decoded, WeakReference<Image> parent) { }

    /** Ключ сравнивает именно объекты Image, даже если подкласс переопределяет equals/hashCode. */
    private static final class ImageIdentity extends WeakReference<Image> {
        private final int hash;
        ImageIdentity(Image image, ReferenceQueue<Image> queue) { super(image, queue); hash = System.identityHashCode(image); }
        /** Хеш остаётся постоянным после освобождения изображения. */
        @Override public int hashCode() { return hash; }
        /** Два живых ключа равны только для одного и того же установленного объекта. */
        @Override public boolean equals(Object other) {
            return this == other || other instanceof ImageIdentity identity && get() != null && get() == identity.get();
        }
    }

    /**
     * Возвращает источник только для объекта, зарегистрированного при настоящем decode/scaling.
     * Проверяет текущие пиксели и ещё живых предков; неизвестная или изменённая цепочка не угадывается.
     * Метод не читает UiIcons, свойства компонента или ресурс повторно.
     */
    static synchronized Optional<DecodedPng> decodedSource(Image installed) {
        releaseSources();
        if (installed == null) return Optional.empty();
        RetainedSource retained = SOURCES.get(new ImageIdentity(installed, null));
        if (retained == null) return Optional.empty();
        Image current = installed;
        for (int index = retained.decoded().chain().size() - 1; index >= 0 && current != null; index--) {
            DecodedImage saved = retained.decoded().chain().get(index);
            RetainedSource entry = SOURCES.get(new ImageIdentity(current, null));
            if (entry == null || !entry.decoded().sourceObjectIdentity().equals(saved.sourceObjectIdentity())) return Optional.empty();
            try {
                if (current.getWidth(null) != saved.width() || current.getHeight(null) != saved.height()
                        || !pixels(current).equals(saved.argbSha256())) return Optional.empty();
            } catch (IOException unreadable) { return Optional.empty(); }
            current = entry.parent() == null ? null : entry.parent().get();
        }
        return Optional.of(retained.decoded());
    }

    /** Декодирует удержанную копию PNG через ImageIO в памяти, не создавая временных файлов. */
    static ImageIcon decode(byte[] bytes, String source) {
        byte[] retained = bytes.clone();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(retained))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Undecodable PNG");
            var reader = readers.next();
            BufferedImage image;
            try {
                if (!reader.getFormatName().equalsIgnoreCase("png")) throw new IOException("Not a PNG");
                reader.setInput(input, true, true);
                image = reader.read(0);
            } finally { reader.dispose(); }
            ImageIcon icon = new ImageIcon(image);
            remember(image, retained, source, List.of(), null, null);
            return icon;
        } catch (IOException failure) { throw new UncheckedIOException("Icon decode", failure); }
    }

    /** Декодирует оригинал через root journal и регистрирует в sidecar именно возвращённый Image. */
    static ImageIcon decode(byte[] bytes, String source, SwingPaintJournal journal) {
        try {
            BufferedImage image = journal.decode(bytes, source);
            remember(image, bytes, source, List.of(), null, null);
            return new ImageIcon(image);
        } catch (IOException failure) { throw new UncheckedIOException("Root icon decode", failure); }
    }

    /** Масштабирует фактический root-local оригинал и удерживает ту же identity в обоих журналах. */
    static ImageIcon scale(Image source, int width, int height, int hints, SwingPaintJournal journal) {
        var retained = decodedSource(source).orElseThrow(() -> new IllegalArgumentException("unknown root icon"));
        try {
            ImageIcon icon = new ImageIcon(journal.scale(source, width, height, hints));
            if (decodedSource(source).filter(value -> value == retained).isEmpty()) throw new IOException("root icon changed while scaling");
            remember(icon.getImage(), retained.bytes(), retained.source(), retained.chain(), source, hints);
            return icon;
        } catch (IOException failure) { throw new UncheckedIOException("Root icon scale", failure); }
    }

    /**
     * Выполняет прежний scaling и загрузку ImageIcon, сохраняя пиксели именно производного объекта.
     * Неизвестный или изменённый родитель сохраняет обычное рисование, но не получает происхождение.
     */
    static ImageIcon scale(Image source, int width, int height, int hints) {
        Optional<DecodedPng> retained = decodedSource(source);
        ImageIcon icon = new ImageIcon(source.getScaledInstance(width, height, hints));
        if (retained.isPresent() && decodedSource(source).filter(value -> value == retained.get()).isPresent()) {
            DecodedPng original = retained.get();
            try { remember(icon.getImage(), original.bytes(), original.source(), original.chain(), source, hints); }
            catch (IOException unreadable) { /* Неготовый производный объект остаётся без provenance. */ }
        }
        return icon;
    }

    /** Регистрирует снимок уже загруженного объекта, не удерживая Image сильной ссылкой. */
    private static synchronized void remember(Image image, byte[] bytes, String source, List<DecodedImage> ancestors,
                                              Image parent, Integer hints) throws IOException {
        releaseSources();
        String digest = pixels(image);
        List<DecodedImage> chain = new ArrayList<>(ancestors);
        chain.add(new DecodedImage("swing-icon-image-" + ++sourceSequence, image.getWidth(null), image.getHeight(null), digest, hints));
        SOURCES.put(new ImageIdentity(image, RELEASED_IMAGES),
                new RetainedSource(new DecodedPng(bytes, source, chain), parent == null ? null : new WeakReference<>(parent)));
    }

    /** Удаляет только записи объектов, которые больше нигде не установлены и не удерживаются кэшем. */
    private static void releaseSources() {
        java.lang.ref.Reference<? extends Image> released;
        while ((released = RELEASED_IMAGES.poll()) != null) SOURCES.remove(released);
    }

    /** Хеширует прямой ARGB объекта без рисования на дополнительный canvas и без перезаписи пикселей. */
    private static String pixels(Image image) throws IOException {
        int width = image.getWidth(null), height = image.getHeight(null);
        if (width <= 0 || height <= 0) throw new IOException("Image pixels unavailable");
        int[] argb;
        if (image instanceof BufferedImage buffered) argb = buffered.getRGB(0, 0, width, height, null, 0, width);
        else {
            PixelGrabber grabber = new PixelGrabber(image, 0, 0, width, height, true);
            try {
                // ImageIcon уже выполнил штатную загрузку; lookup не ожидает работу decoder на EDT.
                if (!grabber.grabPixels(1) || (grabber.getStatus() & (java.awt.image.ImageObserver.ERROR | java.awt.image.ImageObserver.ABORT)) != 0)
                    throw new IOException("Image pixels unavailable");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            if (!(grabber.getPixels() instanceof int[] values)) throw new IOException("Image pixels unavailable");
            argb = values;
        }
        ByteBuffer packed = ByteBuffer.allocate(Math.multiplyExact(argb.length, Integer.BYTES));
        for (int pixel : argb) packed.putInt(pixel);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packed.array())); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    /** Читает изображение по значку или техническому имени только через каталог ядра. */
    public static ImageIcon icon(String key) {
        return CACHE.computeIfAbsent(key, name -> decode(UiIcons.png(name).orElseThrow(() -> new IllegalArgumentException(name)), "UiIcons.png(" + name + ")"));
    }

    /**
     * Задаёт отдельный PNG действия, сохраняя исходный текст для дампа и доступности.
     * Недоступное состояние использует готовый общий цветовой ресурс без перекраски Metal.
     */
    static void standalone(AbstractButton button, String key) {
        standalone(button, key, null);
    }

    /** Назначает root-aware оригиналы до paint, сохраняя обычный overload для остальных окон. */
    static void standalone(AbstractButton button, String key, SwingPaintContext context) {
        button.putClientProperty("cp.text", key);
        button.setText("");
        button.setIcon(icon(key, ColorToken.TEXT_PRIMARY, DesignTokens.INLINE_ICON_SIZE, context));
        button.setDisabledIcon(icon(key, ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE, context));
        button.setIconTextGap(0);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setVerticalAlignment(SwingConstants.CENTER);
        button.getAccessibleContext().setAccessibleName(key);
        button.getAccessibleContext().setAccessibleDescription(
                (String) button.getClientProperty("cp.tooltip"));
    }

    /** Возвращает общий значок приложения из того же физического ресурса, что и другие клиенты. */
    public static Image application() { return decode(UiIcons.applicationPng(), "UiIcons.applicationPng()").getImage(); }

    /** Масштабирует исходный PNG, не прибегая к шрифтовой подстановке. */
    public static ImageIcon icon(String key, int size) {
        return icon(key, size, size);
    }

    /** Загружает цветной вариант исключительно из общего каталога ядра. */
    public static ImageIcon icon(String key, ColorToken color, int size) {
        ImageIcon source = CACHE.computeIfAbsent(key + ":" + color, ignored ->
                decode(UiIcons.png(key, color).orElseThrow(() -> new IllegalArgumentException(key)), "UiIcons.png(" + key + "," + color + ")"));
        return scale(source.getImage(), size, size, Image.SCALE_SMOOTH);
    }

    /** Выбирает явно переданный root context, не используя общий cache для доказательных объектов. */
    static ImageIcon icon(String key, ColorToken color, int size, SwingPaintContext context) {
        return context == null ? color == null ? icon(key, size) : icon(key, color, size) : context.icon(key, color, size, size);
    }

    /** Рамка является геометрией контрола; выбранность рисуется общим PNG, включая disabled. */
    static Icon controlMark(boolean radio) { return new ControlMark(radio); }

    /** Общая отметка настоящего checkbox/radio сохраняет модель и клавиатурное поведение Metal. */
    private static final class ControlMark implements Icon {
        private final boolean radio;
        ControlMark(boolean radio) { this.radio = radio; }
        /** Возвращает общую ширину встроенного значка. */
        @Override public int getIconWidth() { return DesignTokens.INLINE_ICON_SIZE; }
        /** Возвращает общую высоту встроенного значка. */
        @Override public int getIconHeight() { return DesignTokens.INLINE_ICON_SIZE; }
        /** Рисует рамку контрола и растровую выбранность без зависимости от шрифта подписи. */
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            if (!(component instanceof AbstractButton button)) return;
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setColor(SwingLook.color(button.isEnabled() ? ColorToken.TEXT_MUTED : ColorToken.BORDER));
                int edge = DesignTokens.INLINE_ICON_SIZE - 1;
                if (radio) copy.drawOval(x, y, edge, edge); else copy.drawRect(x, y, edge, edge);
                if (button.isSelected()) {
                    if (!button.isEnabled()) copy.setComposite(AlphaComposite.SrcOver.derive(0.55f));
                    icon(radio ? "●" : "✓", button.isEnabled() ? ColorToken.ACCENT : ColorToken.TEXT_MUTED,
                            DesignTokens.INLINE_ICON_SIZE).paintIcon(component, copy, x, y);
                }
            } finally { copy.dispose(); }
        }
    }

    /** Вписывает общий PNG в заданные метрики стрелки существующего контрола. */
    static ImageIcon icon(String key, int width, int height) {
        return scale(icon(key).getImage(), width, height, Image.SCALE_SMOOTH);
    }

    /** Возвращает штатную отметку выбранности меню с изображением из общего каталога. */
    static Icon menuMark(String key) { return new MenuMark(icon(key, DesignTokens.INLINE_ICON_SIZE)); }

    /** Пустое место невыбранного пункта и общий PNG выбранного сохраняют поведение настоящего меню. */
    private static final class MenuMark implements Icon {
        private final ImageIcon selected;
        MenuMark(ImageIcon selected) { this.selected = selected; }
        /** Сохраняет место отметки во всех пунктах. */
        @Override public int getIconWidth() { return DesignTokens.INLINE_ICON_SIZE; }
        /** Сохраняет высоту штатной отметки. */
        @Override public int getIconHeight() { return DesignTokens.INLINE_ICON_SIZE; }
        /** Показывает PNG только при выбранной модели кнопки. */
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            if (component instanceof AbstractButton button && button.isSelected()) selected.paintIcon(component, graphics, x, y);
        }
    }

    /** Назначает рисование декоративных значков, сохраняя исходный текст и его метрики. */
    static void decorate(JLabel label) {
        Font font = label.getFont(); label.setUI(new RasterLabelUI()); label.setFont(font);
    }

    /** Назначает рисование декоративных значков настоящей кнопке Metal. */
    static void decorate(AbstractButton button) {
        decorate(button, null);
    }

    /** Передаёт владельца оригинальных PNG конкретному UI кнопки. */
    static void decorate(AbstractButton button, SwingPaintContext context) {
        Font font = button.getFont(); button.setUI(new RasterButtonUI(context)); button.setFont(font);
    }

    /** Подменяет только графику стрелки списка; выбор, клавиатуру и редактор обслуживает BasicComboBoxUI. */
    static void arrows(JComboBox<?> combo) {
        combo.setUI(new BasicComboBoxUI() {
            /** Создаёт штатную кнопку со стрелкой из общего PNG. */
            @Override protected JButton createArrowButton() { return arrow("▾"); }
        });
    }

    /** Подменяет графику кнопок шага, сохраняя штатные слушатели и автоповтор Spinner. */
    static void arrows(JSpinner spinner) {
        spinner.setUI(new BasicSpinnerUI() {
            /** Создаёт кнопку увеличения со штатным поведением. */
            @Override protected Component createNextButton() {
                JButton button = arrow("↑"); button.setName("Spinner.nextButton"); installNextButtonListeners(button); return button;
            }
            /** Создаёт кнопку уменьшения со штатным поведением. */
            @Override protected Component createPreviousButton() {
                JButton button = arrow("▾"); button.setName("Spinner.previousButton"); installPreviousButtonListeners(button); return button;
            }
        });
    }

    /** Создаёт доступную кнопку управления без рисования стрелки средствами клиента. */
    private static JButton arrow(String key) {
        JButton button = new JButton(icon(key, DesignTokens.INLINE_ICON_SIZE)); button.setMargin(new Insets(0, 0, 0, 0));
        button.setDisabledIcon(icon(key, ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE));
        button.getAccessibleContext().setAccessibleName(key); return button;
    }

    /** Заменяет декоративные символы в измеряемой строке; валюты и стрелки обычной прозы остаются текстом. */
    static AttributedString attributed(String text, Font font) {
        return attributed(text, font, ColorToken.TEXT_PRIMARY);
    }

    /** Сохраняет логические символы и применяет цветовые варианты из ядра. */
    static AttributedString attributed(String text, Font font, ColorToken color) {
        return attributed(text, font, color, null, null);
    }

    /** Связывает реальный TextLayout painter и root-local decode без подмены его метрик. */
    private static AttributedString attributed(String text, Font font, ColorToken color,
                                               SwingPaintContext context, JComponent painter) {
        AttributedString result = new AttributedString(text); result.addAttribute(TextAttribute.FONT, font);
        boolean marks = text.codePoints().filter(cp -> !Character.isWhitespace(cp))
                .allMatch(cp -> UiIcons.manifest().containsKey(new String(Character.toChars(cp))) && cp != '₽');
        for (int offset = 0; offset < text.length();) {
            int end = offset + Character.charCount(text.codePointAt(offset)); String key = text.substring(offset, end);
            boolean boundary = (offset == 0 || Character.isWhitespace(text.charAt(offset - 1)))
                    && (end == text.length() || Character.isWhitespace(text.charAt(end)));
            boolean decoration = !key.equals("₽") && (!key.equals("→") || marks);
            if (boundary && decoration && UiIcons.manifest().containsKey(key)) {
                ColorToken variant = key.equals("Δ") ? ColorToken.WHATIF : color;
                result.addAttribute(TextAttribute.CHAR_REPLACEMENT, new RasterGlyph(
                        icon(key, variant, DesignTokens.INLINE_ICON_SIZE, context), font, key, context, painter), offset, end);
            }
            offset = end;
        }
        return result;
    }

    /** Измеряет строку по общим размерам PNG, а остальной текст по исходному шрифту. */
    static int textWidth(String text, Font font) {
        if (text == null || text.isEmpty()) return 0;
        Graphics2D graphics = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
        try { return textWidth(text, graphics.getFontMetrics(font)); } finally { graphics.dispose(); }
    }

    /** Сохраняет штатные метрики обычного текста и меняет только место декоративного PNG. */
    static int textWidth(String text, FontMetrics metrics) {
        if (text == null || text.isEmpty()) return 0;
        int width = metrics.stringWidth(text);
        var iterator = attributed(text, metrics.getFont()).getIterator();
        for (int offset = 0; offset < text.length();) {
            int end = offset + Character.charCount(text.codePointAt(offset));
            iterator.setIndex(offset);
            if (iterator.getAttribute(TextAttribute.CHAR_REPLACEMENT) != null)
                width += DesignTokens.INLINE_ICON_SIZE - metrics.stringWidth(text.substring(offset, end));
            offset = end;
        }
        return width;
    }

    /** Подставляет размеры только декоративных символов при штатной раскладке метки. */
    private static FontMetrics iconMetrics(FontMetrics source) {
        return new FontMetrics(source.getFont()) {
            /** Измеряет ширину декоративного текста без зависимости от доступности глифов. */
            @Override public int stringWidth(String text) { return textWidth(text, source); }
            /** Сохраняет высоту исходного шрифта подписи. */
            @Override public int getHeight() { return source.getHeight(); }
            /** Сохраняет исходный baseline подписи. */
            @Override public int getAscent() { return source.getAscent(); }
            /** Сохраняет нижнюю часть строки. */
            @Override public int getDescent() { return source.getDescent(); }
            /** Сохраняет исходный межстрочный интервал. */
            @Override public int getLeading() { return source.getLeading(); }
            /** Измеряет обычные символы тем же шрифтом. */
            @Override public int charWidth(char c) { return source.charWidth(c); }
        };
    }

    /** Рисует строку по прежнему baseline и прежней ширине символов, заменяя только их изображение. */
    private static void paint(Graphics graphics, JComponent component, String text, int x, int baseline) {
        paint(graphics, component, text, x, baseline, null);
    }

    /** Использует context фактической кнопки для растровых GraphicAttribute внутри TextLayout. */
    private static void paint(Graphics graphics, JComponent component, String text, int x, int baseline, SwingPaintContext context) {
        if (text == null || text.isEmpty()) return;
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            Color textColor = component.isEnabled() ? component.getForeground()
                    : UIManager.getColor(component instanceof AbstractButton ? "Button.disabledText" : "Label.disabledForeground");
            if (textColor == null) textColor = component.getForeground().darker();
            copy.setColor(textColor);
            if (!component.isEnabled()) copy.setComposite(AlphaComposite.SrcOver.derive(0.55f));
            ColorToken color = ColorToken.byArgb(textColor.getRGB()).orElse(ColorToken.TEXT_PRIMARY);
            new TextLayout(attributed(text, component.getFont(), color, context, component).getIterator(), copy.getFontRenderContext()).draw(copy, x, baseline);
        } finally { copy.dispose(); }
    }

    /** Графический символ занимает исходные метрики текста, но рисует исключительно PNG ядра. */
    private static final class RasterGlyph extends GraphicAttribute {
        private final Image image;
        private final SwingPaintContext context;
        private final JComponent painter;
        private final float advance, ascent, descent;
        RasterGlyph(ImageIcon icon, Font font, String key, SwingPaintContext context, JComponent painter) {
            super(ROMAN_BASELINE); image = icon.getImage();
            this.context = context; this.painter = painter;
            var fontContext = new java.awt.font.FontRenderContext(null, true, true);
            var metrics = font.getLineMetrics(key, fontContext);
            advance = DesignTokens.INLINE_ICON_SIZE; ascent = metrics.getAscent(); descent = metrics.getDescent();
        }
        /** Сохраняет исходную высоту над строкой. */
        @Override public float getAscent() { return ascent; }
        /** Сохраняет исходную высоту под строкой. */
        @Override public float getDescent() { return descent; }
        /** Резервирует общую ширину встроенного значка для настоящей раскладки. */
        @Override public float getAdvance() { return advance; }
        /** Рисует общий PNG в фиксированном размере, не зависящем от глифа шрифта. */
        @Override public void draw(Graphics2D graphics, float x, float y) {
            int size = DesignTokens.INLINE_ICON_SIZE;
            int left = Math.round(x + (advance - size) / 2), top = Math.round(y - ascent + (ascent + descent - size) / 2);
            if (context == null) graphics.drawImage(image, left, top, size, size, null);
            else context.image(painter, graphics, image, left, top, size, size, null, "text-glyph");
        }
    }

    /** Метка сохраняет настоящий текст для доступности и наблюдения. */
    private static final class RasterLabelUI extends BasicLabelUI {
        /** Штатная раскладка резервирует полные 16 px для каждого PNG, включая отсутствующие глифы шрифта. */
        @Override protected String layoutCL(JLabel label, FontMetrics metrics, String text, Icon icon,
                Rectangle view, Rectangle iconBounds, Rectangle textBounds) {
            return super.layoutCL(label, Boolean.FALSE.equals(label.getClientProperty("cp.decorative")) ? metrics : iconMetrics(metrics),
                    text, icon, view, iconBounds, textBounds);
        }
        /** Высота настоящей метки вмещает PNG и при мелком шрифте подписи. */
        @Override public Dimension getPreferredSize(JComponent component) {
            Dimension size = super.getPreferredSize(component);
            if (component instanceof JLabel label && !Boolean.FALSE.equals(label.getClientProperty("cp.decorative"))
                    && label.getText() != null && !label.getText().isEmpty() && !label.getText().startsWith("<html>")) {
                var iterator = attributed(label.getText(), label.getFont()).getIterator();
                for (int index = 0; index < iterator.getEndIndex(); index++) {
                    iterator.setIndex(index);
                    if (iterator.getAttribute(TextAttribute.CHAR_REPLACEMENT) != null) {
                        Insets insets = label.getInsets();
                        size.height = Math.max(size.height, DesignTokens.INLINE_ICON_SIZE + insets.top + insets.bottom);
                        break;
                    }
                }
            }
            return size;
        }
        /** Рисует доступный текст с растровыми декоративными символами. */
        @Override protected void paintEnabledText(JLabel label, Graphics graphics, String text, int x, int y) {
            if (Boolean.FALSE.equals(label.getClientProperty("cp.decorative"))) super.paintEnabledText(label, graphics, text, x, y);
            else SwingIcons.paint(graphics, label, text, x, y);
        }
        /** Не возвращается к шрифтовым значкам при отключении метки. */
        @Override protected void paintDisabledText(JLabel label, Graphics graphics, String text, int x, int y) {
            if (Boolean.FALSE.equals(label.getClientProperty("cp.decorative"))) super.paintDisabledText(label, graphics, text, x, y);
            else SwingIcons.paint(graphics, label, text, x, y);
        }
    }

    /** Кнопка сохраняет Metal, состояние модели и исходную подпись. */
    private static final class RasterButtonUI extends MetalButtonUI {
        private final SwingPaintContext context;
        /** Сохраняет явно переданное root-local владение декоративными PNG. */
        RasterButtonUI(SwingPaintContext context) { this.context = context; }
        /** Резервирует полную ширину встроенных PNG без изменения шрифта или полей кнопки. */
        @Override public Dimension getPreferredSize(JComponent component) {
            Dimension size = super.getPreferredSize(component);
            if (component instanceof AbstractButton button && button.getText() != null) {
                size.width += textWidth(button.getText(), button.getFontMetrics(button.getFont()))
                        - button.getFontMetrics(button.getFont()).stringWidth(button.getText());
            }
            return size;
        }
        /** Заменяет только рисование текста штатной кнопки. */
        @Override protected void paintText(Graphics graphics, JComponent component, Rectangle bounds, String text) {
            int width = textWidth(text, component.getFontMetrics(component.getFont()));
            SwingIcons.paint(graphics, component, text, bounds.x + (bounds.width - width) / 2,
                    bounds.y + component.getFontMetrics(component.getFont()).getAscent(), context);
        }
    }

    /** Назначает отдельный значок заголовка, сохраняя его семантику в клиентском свойстве. */
    static void header(JLabel label, String glyph, String fallback) {
        header(label, glyph, fallback, ColorToken.ACCENT, DesignTokens.DIALOG_ICON_SIZE);
    }

    /** Тип сообщения определяет размер и общий цвет PNG, не затрагивая логическое значение глифа. */
    static void header(JLabel label, String glyph, String fallback, ColorToken color, int size) {
        label.putClientProperty("cp.glyph", glyph); label.setText("");
        label.setIcon(glyph.isEmpty() && fallback.isEmpty() ? null : icon(glyph.isEmpty() ? fallback : glyph,
                color, size));
        label.getAccessibleContext().setAccessibleName(glyph.isEmpty() ? fallback : glyph);
    }

    /** Отделяет значок проблемы от HTML текста, не меняя исходную локализованную подпись. */
    static void problem(JLabel label, String text, int width) {
        String painted = text; label.setIcon(null);
        int end = text.indexOf(' ');
        if (end > 0 && UiIcons.manifest().containsKey(text.substring(0, end))) {
            label.setIcon(icon(text.substring(0, end), ColorToken.byArgb(label.getForeground().getRGB()).orElse(ColorToken.TEXT_PRIMARY),
                    DesignTokens.INLINE_ICON_SIZE)); painted = text.substring(end + 1);
        }
        label.setText(SwingLook.html(painted, width)); label.putClientProperty("cp.text", text);
        label.getAccessibleContext().setAccessibleName(text);
    }

    /** Встраивает в HTML физический ресурс ядра; alt сохраняет исходный символ для доступности. */
    static String htmlImage(String key, ColorToken color) {
        String path = UiIcons.manifest().get(key);
        if (path == null) return SwingLook.escape(key);
        String filename = path.substring(path.lastIndexOf('/') + 1);
        String alias = String.format(java.util.Locale.ROOT, "%s-%s", filename.substring(0, filename.length() - 4),
                color.name().toLowerCase(java.util.Locale.ROOT));
        String variant = UiIcons.manifest().get(alias);
        if (variant != null) filename = variant.substring(variant.lastIndexOf('/') + 1);
        java.net.URL url = UiIcons.class.getResource("/ru/cashprediction/core/ui/icons/" + filename);
        if (url == null) throw new IllegalStateException(filename);
        return "<img src='" + url.toExternalForm() + "' width='" + DesignTokens.INLINE_ICON_SIZE
                + "' height='" + DesignTokens.INLINE_ICON_SIZE + "' alt='" + SwingLook.escape(key) + "'>";
    }

    /** JFileChooser является Swing-диалогом; его FileView не должен загружать системные значки файлов. */
    static void chooser(JFileChooser chooser) {
        // JavaFX: FileChooser / DirectoryChooser → Swing: JFileChooser + FileView → Web: FILE_BROWSER
        chooser.setFileView(new javax.swing.filechooser.FileView() {
            /** Возвращает общую папку или общий значок документа вместо значка оболочки ОС. */
            @Override public Icon getIcon(java.io.File file) {
                return icon(file != null && file.isDirectory() ? "folder" : "≡",
                        ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE);
            }
        });
        chooserButtons(chooser);
    }

    /** Сохраняет общий ресурс и в отключённых кнопках навигации вместо автоматической растровой перекраски LAF. */
    private static void chooserButtons(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JComboBox<?> combo) arrows(combo);
            if (child instanceof AbstractButton button && button.getIcon() != null) {
                button.setDisabledIcon(button.getIcon()); button.setDisabledSelectedIcon(button.getSelectedIcon());
            }
            if (child instanceof Container nested) chooserButtons(nested);
        }
    }
}
