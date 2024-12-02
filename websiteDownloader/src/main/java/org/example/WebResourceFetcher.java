package org.example;



import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class WebResourceFetcher {

    // PostgreSQL configuration
    private static final String DB_CONNECTION = "jdbc:postgresql://localhost:5432/downloader";
    private static final String DB_USERNAME = "postgres";
    private static final String DB_PASSWORD = "klgwn";

    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);
        System.out.print("Input website URL: ");
        String websiteURL = input.next();

        if (!isURLValid(websiteURL)) {
            System.out.println("The provided URL is invalid. Please retry with a correct one.");
            return;
        }

        try {
            String hostName = extractHostName(websiteURL);
            String outputDir = initializeOutputDir(hostName);
            Connection dbConnection = connectToDB();

            if (dbConnection != null) {
                long siteRecordId = addWebsiteEntry(dbConnection, hostName);
                LocalDateTime startTimestamp = LocalDateTime.now();


                File mainPageFile = fetchAndSavePage(websiteURL, outputDir, "home.html");
                System.out.println("Homepage stored at: " + mainPageFile.getAbsolutePath());


                List<String> externalURLs = parseExternalLinks(mainPageFile);
                for (String externalURL : externalURLs) {
                    long startTime = System.currentTimeMillis();
                    System.out.println("Downloading: " + externalURL);
                    try {
                        File savedFile = fetchAndSavePage(externalURL, outputDir, generateFileName(externalURL));
                        long endTime = System.currentTimeMillis();
                        long elapsedMillis = endTime - startTime;
                        long fileSizeInKB = savedFile.length() / 1024;
                        System.out.printf("Resource fetched: %s (%d KB) in %d ms%n", externalURL, fileSizeInKB, elapsedMillis);

                        addResourceEntry(dbConnection, siteRecordId, externalURL, elapsedMillis, fileSizeInKB);
                    } catch (Exception ex) {
                        System.out.println("Error downloading: " + externalURL);
                    }
                }


                LocalDateTime endTimestamp = LocalDateTime.now();
                finalizeSiteEntry(dbConnection, siteRecordId, startTimestamp, endTimestamp, computeTotalSize(outputDir));
                System.out.println("Process completed successfully!");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }


    private static boolean isURLValid(String url) {
        String pattern = "^(https?://)?(www\\.)?[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$";
        return Pattern.matches(pattern, url);
    }


    private static String extractHostName(String url) throws URISyntaxException {
        URI parsedURI = new URI(url);
        String host = parsedURI.getHost();
        return (host != null) ? host.replace("www.", "") : "unknown_site";
    }


    private static String initializeOutputDir(String host) {
        String path = "./" + host;
        new File(path).mkdirs();
        return path;
    }


    private static File fetchAndSavePage(String pageURL, String saveDir, String filename) throws IOException {
        Document pageContent = Jsoup.connect(pageURL).get();
        File targetFile = new File(saveDir, filename);
        try (BufferedWriter fileWriter = new BufferedWriter(new FileWriter(targetFile))) {
            fileWriter.write(pageContent.html());
        }
        return targetFile;
    }


    private static List<String> parseExternalLinks(File file) throws IOException {
        Document doc = Jsoup.parse(file, "UTF-8");
        Elements linkElements = doc.select("a[href]");
        List<String> externalURLs = new ArrayList<>();
        for (Element link : linkElements) {
            String href = link.attr("abs:href");
            if (isURLValid(href)) {
                externalURLs.add(href);
            }
        }
        return externalURLs;
    }


    private static String generateFileName(String resourceURL) throws MalformedURLException {
        String path = new URL(resourceURL).getPath();
        return path.isEmpty() ? "default.html" : path.substring(path.lastIndexOf('/') + 1);
    }


    private static Connection connectToDB() {
        try {
            return DriverManager.getConnection(DB_CONNECTION, DB_USERNAME, DB_PASSWORD);
        } catch (SQLException e) {
            System.out.println("Failed to connect to the database.");
            e.printStackTrace();
            return null;
        }
    }


    private static long addWebsiteEntry(Connection conn, String host) throws SQLException {
        String query = "INSERT INTO website (website_name, download_start_date_time) VALUES (?, ?) RETURNING id";
        try (PreparedStatement statement = conn.prepareStatement(query)) {
            statement.setString(1, host);
            statement.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) return resultSet.getLong(1);
        }
        throw new SQLException("Could not save the website record.");
    }


    private static void addResourceEntry(Connection conn, long siteId, String resource, long duration, long sizeKB) throws SQLException {
        String sql = "INSERT INTO link (link_name, website_id, total_elapsed_time, total_downloaded_kilobytes) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resource);
            ps.setLong(2, siteId);
            ps.setLong(3, duration);
            ps.setLong(4, sizeKB);
            ps.executeUpdate();
        }
    }


    private static void finalizeSiteEntry(Connection conn, long siteId, LocalDateTime start, LocalDateTime end, long totalSizeKB) throws SQLException {
        String updateSQL = "UPDATE website SET download_end_date_time = ?, total_elapsed_time = ?, total_downloaded_kilobytes = ? WHERE id = ?";
        try (PreparedStatement updateStmt = conn.prepareStatement(updateSQL)) {
            updateStmt.setTimestamp(1, Timestamp.valueOf(end));
            updateStmt.setLong(2, Duration.between(start, end).toMillis());
            updateStmt.setLong(3, totalSizeKB);
            updateStmt.setLong(4, siteId);
            updateStmt.executeUpdate();
        }
    }


    private static long computeTotalSize(String dir) {
        return Arrays.stream(new File(dir).listFiles())
                .mapToLong(File::length)
                .sum() / 1024;
    }
}

