package org.commonhaus.automation.markdown;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import io.quarkus.logging.Log;

public class MarkdownConverter {
    static final Parser parser = Parser.builder().build();
    static final HtmlRenderer renderer = HtmlRenderer.builder().build();

    public static String toHtml(String body) {
        if (body == null) {
            return "<p></p>";
        }
        try {
            // Do not include HTML comments in the rendered email body
            Node document = parser.parse(body);
            return renderer.render(document);
        } catch (Exception e) {
            Log.errorf(e, "toHtml: Failed to render body as HTML");
            return body;
        }
    }
}
