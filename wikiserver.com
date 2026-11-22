import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class WikiServer {
    private static final Map<String, String> pages = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        pages.put("トップページ", "Undertale Scratch AU Wikiへようこそ！\n下のフォームから新しいAUのページを追加できるぜ！");

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", WikiServer::handleRequest);
        server.setExecutor(null);
        server.start();
        System.out.println("Wikiサーバー起動完了！ポート8080で待機中...");
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getQuery();

        if ("POST".equalsIgnoreCase(method)) {
            InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
            BufferedReader br = new BufferedReader(isr);
            String formData = br.readLine();
            if (formData != null) {
                String title = "";
                String content = "";
                for (String param : formData.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2) {
                        String key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                        String val = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                        if ("title".equals(key)) title = val.trim();
                        if ("content".equals(key)) content = val;
                    }
                }
                if (!title.isEmpty()) {
                    pages.put(title, content);
                }
            }
            exchange.getResponseHeaders().add("Location", "/");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        String currentPage = "トップページ";
        if (query != null && query.startsWith("page=")) {
            currentPage = URLDecoder.decode(query.substring(5), StandardCharsets.UTF_8);
        }
        String bodyText = pages.getOrDefault(currentPage, "まだこのページは作られてないよ！");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ja'><head><meta charset='UTF-8'>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        html.append("<title>Undertale Scratch AU Wiki</title>");
        html.append("<style>");
        html.append("body { font-family: sans-serif; margin: 15px; background: #121212; color: #eee; }");
        html.append(".card { background: #1e1e1e; padding: 15px; border-radius: 8px; border: 1px solid #333; margin-bottom: 15px; }");
        html.append("input, textarea { width: 100%; box-sizing: border-box; margin-bottom: 10px; padding: 10px; background: #2c2c2c; color: white; border: 1px solid #555; border-radius: 4px; }");
        html.append("button { background: #ff4757; color: white; border: none; padding: 12px; border-radius: 4px; font-size: 16px; font-weight: bold; width: 100%; }");
        html.append("a { color: #ffdd59; text-decoration: none; margin-right: 10px; display: inline-block; margin-bottom: 5px; }");
        html.append("</style></head><body>");

        html.append("<h2>❤️ Undertale Scratch AU Wiki</h2>");
        
        html.append("<div class='card'><strong>📚 登録済みAU一覧:</strong><br><br>");
        for (String title : pages.keySet()) {
            html.append("<a href='/?page=").append(java.net.URLEncoder.encode(title, StandardCharsets.UTF_8)).append("'>★ ").append(escapeHtml(title)).append("</a> ");
        }
        html.append("</div>");

        html.append("<div class='card'><h3>").append(escapeHtml(currentPage)).append("</h3>");
        html.append("<p style='white-space: pre-wrap; line-height: 1.6;'>").append(escapeHtml(bodyText)).append("</p></div>");

        html.append("<div class='card'><h4>✏️ 新しいAU記事を作る・編集する</h4>");
        html.append("<form method='POST' action='/'>");
        html.append("<input type='text' name='title' placeholder='AUの名前（例：Ink!Sans、Underswapなど）' required>");
        html.append("<textarea name='content' rows='5' placeholder='ここにAUの設定やストーリーを書く...' required></textarea>");
        html.append("<button type='submit'>セーブする</button></form></div>");

        html.append("</body></html>");

        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
