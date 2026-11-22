cat << 'EOF' > WikiServer.java
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.regex.*;

public class WikiServer {
    private static final Map<String, String> pages = new ConcurrentHashMap<>();
    private static final String DATA_FILE = "wiki_data.txt";
    // ★ここが管理者パスワード！好きなものに変更OK
    private static final String ADMIN_PASS = "sans1234";

    public static void main(String[] args) throws IOException {
        loadData();
        if (pages.isEmpty()) {
            pages.put("トップページ", "Undertale Scratch AU Wikiへようこそ！\nScratchのプロジェクトURLや画像URLを貼ると自動で埋め込まれるぜ！");
            saveData();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", WikiServer::handleRequest);
        server.setExecutor(null);
        server.start();
        System.out.println("Wikiサーバー起動完了（管理者ロック＆埋め込み対応版）！");
    }

    private static void loadData() {
        Path path = Paths.get(DATA_FILE);
        if (!Files.exists(path)) return;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String title = new String(Base64.getDecoder().decode(parts), StandardCharsets.UTF_8);
                    String content = new String(Base64.getDecoder().decode(parts), StandardCharsets.UTF_8);
                    pages.put(title, content);
                }
            }
        } catch (Exception e) {
            System.out.println("読み込みエラー: " + e.getMessage());
        }
    }

    private static synchronized void saveData() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : pages.entrySet()) {
                String encTitle = Base64.getEncoder().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
                String encContent = Base64.getEncoder().encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8));
                sb.append(encTitle).append(":").append(encContent).append("\n");
            }
            Files.write(Paths.get(DATA_FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("保存エラー: " + e.getMessage());
        }
    }

    private static boolean isAdmin(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        return cookie != null && cookie.contains("wiki_admin=" + ADMIN_PASS);
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getQuery();
        boolean admin = isAdmin(exchange);

        if ("POST".equalsIgnoreCase(method)) {
            InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
            BufferedReader br = new BufferedReader(isr);
            String formData = br.readLine();
            Map<String, String> params = new ConcurrentHashMap<>();
            if (formData != null) {
                for (String param : formData.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2) {
                        params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8),
                                   URLDecoder.decode(pair, StandardCharsets.UTF_8));
                    }
                }
            }

            String action = params.getOrDefault("action", "");

            // ログイン処理
            if ("login".equals(action)) {
                String pass = params.getOrDefault("password", "");
                if (ADMIN_PASS.equals(pass)) {
                    exchange.getResponseHeaders().add("Set-Cookie", "wiki_admin=" + ADMIN_PASS + "; Path=/; HttpOnly");
                }
                exchange.getResponseHeaders().add("Location", "/");
                exchange.sendResponseHeaders(302, -1);
                return;
            }

            // ログアウト処理
            if ("logout".equals(action)) {
                exchange.getResponseHeaders().add("Set-Cookie", "wiki_admin=; Path=/; Max-Age=0");
                exchange.getResponseHeaders().add("Location", "/");
                exchange.sendResponseHeaders(302, -1);
                return;
            }

            // 管理者のみ：保存・編集
            if (admin && "save".equals(action)) {
                String title = params.getOrDefault("title", "").trim();
                String content = params.getOrDefault("content", "");
                if (!title.isEmpty()) {
                    pages.put(title, content);
                    saveData();
                    exchange.getResponseHeaders().add("Location", "/?page=" + URLEncoder.encode(title, StandardCharsets.UTF_8));
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
            }

            // 管理者のみ：削除
            if (admin && "delete".equals(action)) {
                String target = params.getOrDefault("target", "");
                if (!target.isEmpty() && !"トップページ".equals(target)) {
                    pages.remove(target);
                    saveData();
                }
                exchange.getResponseHeaders().add("Location", "/");
                exchange.sendResponseHeaders(302, -1);
                return;
            }

            exchange.getResponseHeaders().add("Location", "/");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        // GETパラメータ解析
        String currentPage = "トップページ";
        boolean editMode = false;
        if (query != null) {
            for (String q : query.split("&")) {
                if (q.startsWith("page=")) {
                    currentPage = URLDecoder.decode(q.substring(5), StandardCharsets.UTF_8);
                } else if ("edit=1".equals(q)) {
                    editMode = true;
                }
            }
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
        html.append("button { background: #ff4757; color: white; border: none; padding: 10px 15px; border-radius: 4px; font-size: 15px; font-weight: bold; width: 100%; cursor: pointer; }");
        html.append(".btn-edit { background: #ffa502; color: #111; margin-bottom: 8px; }");
        html.append(".btn-del { background: #57606f; color: white; }");
        html.append("a { color: #ffdd59; text-decoration: none; margin-right: 10px; display: inline-block; margin-bottom: 5px; }");
        html.append("img { max-width: 100%; border-radius: 6px; margin: 10px 0; display: block; }");
        html.append("iframe { width: 100%; height: 320px; border: none; border-radius: 6px; margin: 10px 0; }");
        html.append("</style></head><body>");

        html.append("<h2>❤️ Undertale Scratch AU Wiki</h2>");
        
        // ページ一覧
        html.append("<div class='card'><strong>📚 登録済みAU一覧:</strong><br><br>");
        for (String title : pages.keySet()) {
            html.append("<a href='/?page=").append(URLEncoder.encode(title, StandardCharsets.UTF_8)).append("'>★ ").append(escapeHtml(title)).append("</a> ");
        }
        html.append("</div>");

        // 記事表示エリア
        html.append("<div class='card'><h3>").append(escapeHtml(currentPage)).append("</h3>");
        html.append("<div style='line-height: 1.6;'>").append(formatContent(bodyText)).append("</div>");

        // 管理者専用：編集・削除ボタン
        if (admin && pages.containsKey(currentPage)) {
            html.append("<hr style='border-color:#333; margin:15px 0;'>");
            html.append("<a href='/?page=").append(URLEncoder.encode(currentPage, StandardCharsets.UTF_8)).append("&edit=1' style='width:100%;'>");
            html.append("<button type='button' class='btn-edit'>✏️ この記事を編集する</button></a>");
            if (!"トップページ".equals(currentPage)) {
                html.append("<form method='POST' action='/' onsubmit='return confirm(\"本当に削除する？\");'>");
                html.append("<input type='hidden' name='action' value='delete'>");
                html.append("<input type='hidden' name='target' value='").append(escapeHtml(currentPage)).append("'>");
                html.append("<button type='submit' class='btn-del'>🗑️ この記事を削除する</button></form>");
            }
        }
        html.append("</div>");

        // 管理者専用：新規作成・編集フォーム
        if (admin) {
            String editTitle = editMode ? currentPage : "";
            String editBody = editMode ? bodyText : "";
            html.append("<div class='card'><h4>").append(editMode ? "✏️ 記事を編集中" : "➕ 新しいAU記事を作る").append("</h4>");
            html.append("<form method='POST' action='/'>");
            html.append("<input type='hidden' name='action' value='save'>");
