package com.customdimensions.core.score;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code index.html} for a roll: the best seeds, best first, each with its
 * renders, score and scorecard. Every path is relative and nothing loads from
 * outside the folder, so the page opens from disk.
 */
public final class ContactSheet {

    /**
     * One rolled seed on the sheet.
     *
     * @param images    layer name to image file name, in the same folder
     * @param scorecard the scorecard JSON's file name, in the same folder
     * @param legend    structure id to its marker colour on the structures layer
     */
    public record Entry(int rank, Scorecard card, Map<String, String> images, String scorecard, Map<String, String> legend) {}

    private ContactSheet() {}

    /** Ranks scorecards: scored before rejected before invalid, then by percentage, then by seed. */
    public static final Comparator<Scorecard> RANKING = Comparator
            .comparingInt((Scorecard c) -> switch (c.verdict()) {
                case SCORED -> 0;
                case REJECTED -> 1;
                case INVALID_CONFIG -> 2;
            })
            .thenComparing(c -> c.percentage() == null ? 0.0 : -c.percentage())
            .thenComparingLong(Scorecard::seed);

    public static Path write(Path dir, String dimension, int rolled, String summary, List<Entry> entries) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html>\n<html lang=\"en-GB\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(esc(dimension)).append(" roll</title>\n<style>\n")
                .append(":root{--bg:#f6f5f2;--fg:#1d1c1a;--muted:#6b6862;--card:#fff;--line:#dedbd4;--pass:#2f7d4a;--fail:#b3402f}\n")
                .append("@media (prefers-color-scheme: dark){:root{--bg:#161614;--fg:#ecebe7;--muted:#a19d95;--card:#22211e;--line:#3a3833;--pass:#6cc48a;--fail:#e58a78}}\n")
                .append("body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,sans-serif}\n")
                .append("main{max-width:1200px;margin:0 auto}h1{font-size:1.4rem;margin:0 0 4px}p.sub{color:var(--muted);margin:0 0 16px}\n")
                .append("section{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px;margin:0 0 16px}\n")
                .append("h2{font-size:1.1rem;margin:0 0 8px}.imgs{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:8px}\n")
                .append("figure{margin:0}figure img{width:100%;height:auto;image-rendering:pixelated;border:1px solid var(--line)}\n")
                .append("figcaption{color:var(--muted);font-size:.85rem}table{border-collapse:collapse;width:100%;font-size:.85rem}\n")
                .append("td,th{border-top:1px solid var(--line);padding:4px 6px;text-align:left;vertical-align:top}.pass{color:var(--pass)}.fail{color:var(--fail)}\n")
                .append(".swatch{display:inline-block;width:10px;height:10px;margin-right:4px;border:1px solid var(--line)}\n")
                .append(".scroll{overflow-x:auto}\n</style>\n</head>\n<body>\n<main>\n")
                .append("<h1>").append(esc(dimension)).append(": best ").append(entries.size()).append(" of ").append(rolled)
                .append(" seeds</h1>\n<p class=\"sub\">").append(esc(summary)).append("</p>\n");
        for (Entry e : entries) {
            Scorecard c = e.card();
            String pct = c.percentage() == null ? c.verdict().name().toLowerCase(Locale.ROOT)
                    : String.format(Locale.ROOT, "%.1f%%", c.percentage());
            b.append("<section id=\"seed-").append(c.seed()).append("\">\n<h2>#").append(e.rank()).append(" seed ")
                    .append(c.seed()).append(" — ").append(esc(pct)).append("</h2>\n");
            if (!c.verdictReason().isEmpty()) b.append("<p class=\"fail\">").append(esc(c.verdictReason())).append("</p>\n");
            b.append("<div class=\"imgs\">\n");
            for (Map.Entry<String, String> img : e.images().entrySet()) {
                b.append("<figure><img src=\"").append(esc(img.getValue())).append("\" alt=\"").append(esc(img.getKey()))
                        .append(" of seed ").append(c.seed()).append("\"><figcaption>").append(esc(img.getKey()))
                        .append("</figcaption></figure>\n");
            }
            b.append("</div>\n");
            if (!e.legend().isEmpty()) {
                b.append("<p>");
                e.legend().forEach((id, colour) -> b.append("<span class=\"swatch\" style=\"background:").append(esc(colour))
                        .append("\"></span>").append(esc(id)).append(" &nbsp; "));
                b.append("</p>\n");
            }
            b.append("<details><summary>Scorecard (<a href=\"").append(esc(e.scorecard())).append("\">JSON</a>)</summary>\n")
                    .append("<div class=\"scroll\"><table><tr><th>Criterion</th><th>Outcome</th><th>Target</th><th>Detail</th></tr>\n");
            for (Scorecard.Entry row : c.entries()) {
                String outcome = row.value() == null ? row.outcome() : String.format(Locale.ROOT, "%.2f", row.value());
                String cls = "fail".equals(row.outcome()) ? "fail" : "pass".equals(row.outcome()) ? "pass" : "";
                b.append("<tr><td>").append(esc(row.id())).append("</td><td class=\"").append(cls).append("\">")
                        .append(esc(outcome)).append("</td><td>").append(esc(row.target())).append("</td><td>")
                        .append(esc(row.detail())).append("</td></tr>\n");
            }
            b.append("</table></div>\n</details>\n</section>\n");
        }
        b.append("</main>\n</body>\n</html>\n");
        Files.createDirectories(dir);
        Path out = dir.resolve("index.html");
        Path tmp = dir.resolve("index.html.tmp");
        Files.writeString(tmp, b.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return out;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
