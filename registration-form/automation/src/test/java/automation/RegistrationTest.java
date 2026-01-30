package automation;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import static org.junit.jupiter.api.Assertions.*;

public class RegistrationTest {
    static WebDriver driver;
    static Path basePath;
    static HttpServer httpServer;
    static String baseUrl;

    @BeforeAll
    public static void setupClass(){
        WebDriverManager.chromedriver().setup();
        ChromeOptions opts = new ChromeOptions();
        // Run headless by default for CI; remove headless if you want to see the browser
        opts.addArguments("--headless=new", "--disable-gpu");
        // Container-friendly flags
        opts.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--remote-allow-origins=*");

        // Allow overriding Chrome binary via CHROME_BIN env var or try common locations
        String chromeBin = System.getenv("CHROME_BIN");
        if(chromeBin == null){
            if(Files.exists(Paths.get("/usr/bin/google-chrome-stable"))) chromeBin = "/usr/bin/google-chrome-stable";
            else if(Files.exists(Paths.get("/usr/bin/google-chrome"))) chromeBin = "/usr/bin/google-chrome";
            else if(Files.exists(Paths.get("/usr/bin/chromium-browser"))) chromeBin = "/usr/bin/chromium-browser";
            else if(Files.exists(Paths.get("/usr/bin/chromium"))) chromeBin = "/usr/bin/chromium";
        }
        if(chromeBin != null){
            opts.setBinary(chromeBin);
        }

        driver = new ChromeDriver(opts);
        // Resolve the real registration-form directory (works when running from automation or repo root)
        Path cwd = Paths.get("").toAbsolutePath();
        Path found = null;
        Path p = cwd;
        while(p != null){
            Path candidate = p.resolve("registration-form");
            if(Files.exists(candidate.resolve("index.html"))){
                found = candidate.toAbsolutePath().normalize();
                break;
            }
            p = p.getParent();
        }
        if(found != null) basePath = found;
        else basePath = Paths.get("registration-form").toAbsolutePath();

        // Start a lightweight HTTP server to serve the static files during tests
        try {
            // Bind to loopback only to avoid exposing driver/debug ports publicly
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

            // Simple health/status endpoint for quick verification
            httpServer.createContext("/status", exchange -> {
                byte[] ok = "OK".getBytes();
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
                exchange.close();
            });

            httpServer.createContext("/", exchange -> {
                URI req = exchange.getRequestURI();
                String path = req.getPath();
                // Decode the path and normalize
                String decoded = java.net.URLDecoder.decode(path == null ? "" : path, java.nio.charset.StandardCharsets.UTF_8);
                if (decoded == null || decoded.equals("/") || decoded.equals("")) decoded = "/index.html";
                Path file = basePath.resolve(decoded.substring(1)).normalize();
                // If no explicit file exists and the path looks like an app route, serve index.html (SPA-friendly)
                if (!Files.exists(file) && !decoded.contains(".")) {
                    file = basePath.resolve("index.html");
                }
                if (!file.startsWith(basePath) || !Files.exists(file)) {
                    String notFound = "Not found";
                    exchange.sendResponseHeaders(404, notFound.length());
                    exchange.getResponseBody().write(notFound.getBytes());
                    exchange.close();
                    return;
                }
                String contentType = "text/html";
                if (decoded.endsWith(".css")) contentType = "text/css";
                else if (decoded.endsWith(".js")) contentType = "application/javascript";
                else if (decoded.endsWith(".png")) contentType = "image/png";
                byte[] bytes = Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            httpServer.setExecutor(null);
            httpServer.start();
            int port = httpServer.getAddress().getPort();
            baseUrl = "http://127.0.0.1:" + port;
            System.out.println("HTTP server started at " + baseUrl);

            // Write server URL to a known file for CI visibility
            try {
                Path serverFile = Paths.get("registration-form", "automation", "target", "server-url.txt");
                Files.createDirectories(serverFile.getParent());
                Files.writeString(serverFile, baseUrl);
            } catch (IOException e) {
                System.err.println("Failed to write server-url file: " + e.getMessage());
            }

            // Quick status probe to ensure server responds
            try {
                java.net.URL u = new java.net.URL(baseUrl + "/status");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                if(code != 200) throw new RuntimeException("Status endpoint returned " + code);
                try (java.io.InputStream in = conn.getInputStream()) {
                    String body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                    if(!"OK".equals(body)) throw new RuntimeException("Status endpoint body unexpected: '" + body + "'");
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to probe /status endpoint: " + e.getMessage(), e);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to start HTTP server: " + e.getMessage(), e);
        }
    }

    @AfterAll
    public static void tearDownClass(){
        if(driver != null) driver.quit();
        if(httpServer != null){
            // Keep server alive for a short grace period so CI probes can hit /status after tests finish.
            int grace = 5; // seconds (increased default to give CI probe more time)
            try{
                String g = System.getenv("SERVER_GRACE_SECONDS");
                if(g != null) grace = Integer.parseInt(g);
            } catch(Exception ignored){ }
            System.out.println("Keeping server alive for " + grace + " seconds before shutdown (SERVER_GRACE_SECONDS=" + grace + ")");
            try{
                Thread.sleep(grace * 1000L);
            } catch(InterruptedException ignored){ }
            httpServer.stop(0);
        }
    }

    @BeforeEach
    public void openPage(){
        String url = baseUrl + "/index.html";
        driver.get(url);
        // Wait for page to report readyState=complete and for the form to be present
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        wait.until(webDriver -> ((JavascriptExecutor) webDriver).executeScript("return document.readyState").equals("complete"));
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.id("firstName")));
        } catch (Exception e) {
            // Dump page source to help debugging
            try {
                String page = driver.getPageSource();
                Path dump = basePath.resolve("screenshots/page-dump.html");
                Files.createDirectories(dump.getParent());
                Files.writeString(dump, page);
                System.err.println("Wrote page dump to: " + dump);
            } catch (IOException io){
                System.err.println("Failed to write page dump: " + io.getMessage());
            }
            throw e;
        }
    }

    @Test
    public void negative_missingLastName(){
        driver.findElement(By.id("firstName")).sendKeys("John");
        // lastName omitted intentionally
        driver.findElement(By.id("email")).sendKeys("john.doe@example.com");
        driver.findElement(By.id("phone")).sendKeys("9876543210");
        driver.findElements(By.name("gender")).get(0).click();
        WebElement country = driver.findElement(By.id("country"));
        country.findElement(By.xpath("//option[.='India']")).click();
        WebElement state = driver.findElement(By.id("state"));
        state.findElement(By.xpath("//option[.='Karnataka']")).click();
        WebElement city = driver.findElement(By.id("city"));
        city.findElement(By.xpath("//option[.='Bengaluru']")).click();
        driver.findElement(By.id("password")).sendKeys("Password123");
        driver.findElement(By.id("confirmPassword")).sendKeys("Password123");
        driver.findElement(By.id("terms")).click();

        // Try to submit
        WebElement submit = driver.findElement(By.id("submitBtn"));
        submit.click();

        // The form prevents submission; verify last name error is shown
        WebElement lastNameError = driver.findElement(By.id("lastNameError"));
        assertTrue(lastNameError.isDisplayed(), "Expected last name error message to be visible");

        takeScreenshot("screenshots/error-state.png");
    }

    @Test
    public void positive_successfulSubmission(){
        driver.findElement(By.id("firstName")).sendKeys("Jane");
        driver.findElement(By.id("lastName")).sendKeys("Doe");
        driver.findElement(By.id("email")).sendKeys("jane.doe@example.com");
        driver.findElement(By.id("phone")).sendKeys("9123456789");
        driver.findElements(By.name("gender")).get(1).click();
        WebElement country = driver.findElement(By.id("country"));
        country.findElement(By.xpath("//option[.='USA']")).click();
        WebElement state = driver.findElement(By.id("state"));
        state.findElement(By.xpath("//option[.='California']")).click();
        WebElement city = driver.findElement(By.id("city"));
        city.findElement(By.xpath("//option[.='Los Angeles']")).click();
        driver.findElement(By.id("password")).sendKeys("Secret123");
        driver.findElement(By.id("confirmPassword")).sendKeys("Secret123");
        driver.findElement(By.id("terms")).click();

        WebElement submit = driver.findElement(By.id("submitBtn"));
        // Wait until enabled (simple spin wait)
        for(int i=0;i<20;i++){
            if(submit.isEnabled()) break;
            try{ Thread.sleep(200); } catch(InterruptedException ignored){}
        }
        assertTrue(submit.isEnabled(), "Submit button should be enabled for valid form");
        submit.click();

        // Verify success message
        WebElement success = driver.findElement(By.id("successMessage"));
        assertTrue(success.isDisplayed(), "Success message should appear");

        takeScreenshot("screenshots/success-state.png");

        // Verify reset - first name should be empty after successful submit
        assertEquals("", driver.findElement(By.id("firstName")).getAttribute("value"));
    }

    private void takeScreenshot(String relativePath){
        try{
            File src = ((TakesScreenshot)driver).getScreenshotAs(OutputType.FILE);
            Path target = basePath.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.copy(src.toPath(), target);
        } catch(IOException e){
            System.err.println("Failed to save screenshot: " + e.getMessage());
        }
    }
}
