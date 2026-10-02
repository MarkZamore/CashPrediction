package ru.cashprediction.parity.check.visual;

import java.nio.file.*;
import java.util.*;
import ru.cashprediction.parity.launch.ReactorLayout;

/** Собирает независимый визуальный отчёт с реальными снимками, попарной геометрией и наложением. */
public final class VisualSuite {
    private VisualSuite() { }

    /** Итог содержит ошибки и ссылку на сохранённый отчёт даже при отсутствующем PNG. */
    public record Result(List<String> failures, Path report) {
        /** Требует успешную проверку всех запрошенных клиентов и точек. */
        public void requireSuccess() {
            if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures) + "\nVisual report: " + report);
        }
    }

    /** Запускает выбранные реальные клиенты последовательно, чтобы Robot не снимал чужое тестовое окно. */
    public static Result run(ReactorLayout layout, List<String> clients, List<VisualPlan.Checkpoint> checkpoints) throws Exception {
        if (clients.isEmpty() || checkpoints.isEmpty()) throw new IllegalArgumentException("Empty real visual matrix");
        Path output = Files.createDirectories(layout.parityRoot().resolve("visual-" + UUID.randomUUID()));
        var captures = new ArrayList<VisualRun.Capture>(); var failures = new ArrayList<String>();
        for (var checkpoint : checkpoints) {
            var group = new ArrayList<VisualRun.Capture>();
            for (String client : clients) {
                var capture = VisualRun.collect(layout, client, checkpoint, output); captures.add(capture); group.add(capture);
                for (String failure : capture.failures()) failures.add(checkpoint.scenario() + "/" + checkpoint.step() + "/" + client + ": " + failure);
            }
            for (int i = 0; i < group.size(); i++) for (int j = i + 1; j < group.size(); j++) {
                var a = group.get(i); var b = group.get(j);
                if (a.dump() == null || b.dump() == null) { failures.add("Pairwise geometry unavailable: " + a.client() + "/" + b.client()); continue; }
                try {
                    for (String failure : VisualImages.compare(a.dump(), b.dump())) failures.add(checkpoint.scenario() + "/" + checkpoint.step()
                            + "/" + a.client() + " vs " + b.client() + ": " + failure);
                } catch (Exception | AssertionError failure) { failures.add("Pairwise geometry: " + failure); }
            }
        }
        Path report = writeReport(output, captures, failures, clients.size() > 1);
        return new Result(List.copyOf(failures), report);
    }

    /** Пишет только ссылки на фактические PNG; никаких заменителей отсутствующих снимков не создаёт. */
    public static Path writeReport(Path output, List<VisualRun.Capture> captures, List<String> failures, boolean pairwise) throws Exception {
        Files.createDirectories(output);
        StringBuilder html = new StringBuilder("<!doctype html><html lang=ru><meta charset=utf-8><title>Визуальная проверка S3</title>"
                + "<style>body{font:14px sans-serif;margin:24px}section{margin:24px 0}.shots{display:flex;gap:12px;overflow:auto}"
                + "figure{margin:0}img{max-width:none}.shots img{width:600px}.overlay{position:relative;width:1200px;height:800px;overflow:hidden}"
                + ".overlay img{position:absolute;left:0;top:0;width:1200px;height:800px}.errors{white-space:pre-wrap;color:#a11}</style>"
                + "<h1>Настоящие снимки S3</h1><p>Исходные PNG 1200×800. Отчёт независим от золотых файлов. "
                + "Масштаб миниатюр влияет только на просмотр, не на измерения.</p>");
        if (!pairwise) html.append("<p>Выбран один клиент: попарная проверка не запрошена.</p>");
        html.append("<pre class=errors>").append(escape(String.join("\n", failures))).append("</pre>");
        var groups = new LinkedHashMap<String, List<VisualRun.Capture>>();
        for (var capture : captures) groups.computeIfAbsent(capture.checkpoint().scenario() + "/" + capture.checkpoint().step(), k -> new ArrayList<>()).add(capture);
        int index = 0;
        for (var entry : groups.entrySet()) {
            html.append("<section><h2>").append(escape(entry.getKey())).append("</h2><div class=shots>");
            for (var capture : entry.getValue()) {
                html.append("<figure><figcaption>").append(escape(capture.client())).append("</figcaption>");
                if (capture.png() == null) html.append("<p>Настоящий PNG отсутствует.</p>");
                else html.append("<a href=\"").append(url(output, capture.png())).append("\"><img alt=\"")
                        .append(escape(capture.client())).append("\" src=\"").append(url(output, capture.png())).append("\"></a>");
                html.append("<p><a href=\"").append(url(output, capture.run())).append("/\">Сценарий и журналы</a></p></figure>");
            }
            html.append("</div>");
            var images = entry.getValue().stream().filter(c -> c.png() != null).toList();
            if (images.size() > 1) {
                String id = "overlay" + index++;
                html.append("<p>Наложение ").append(escape(images.get(0).client())).append(" / ")
                        .append(escape(images.get(1).client())).append(" <input type=range min=0 max=1 step=.05 value=.5 data-overlay=")
                        .append(id).append("></p><div class=overlay><img alt=base src=\"").append(url(output, images.get(0).png()))
                        .append("\"><img alt=overlay id=").append(id).append(" style=opacity:.5 src=\"")
                        .append(url(output, images.get(1).png())).append("\"></div>");
            }
            html.append("</section>");
        }
        html.append("<script>/** Меняет только прозрачность просмотра, не исходные пиксели. */\n"
                + "function changeOpacity(event){document.getElementById(event.target.dataset.overlay).style.opacity=event.target.value;}\n"
                + "/** Подключает управление просмотром одного наложения. */\n"
                + "function bindOpacity(input){input.addEventListener('input',changeOpacity);}\n"
                + "document.querySelectorAll('[data-overlay]').forEach(bindOpacity);</script></html>");
        Path report = output.resolve("report.html"); Files.writeString(report, html); return report;
    }

    private static String url(Path output, Path path) { return escape(output.relativize(path).toString().replace('\\', '/')); }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
