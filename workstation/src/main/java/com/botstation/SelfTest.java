package com.botstation;

import com.botstation.core.BotPaths;
import com.botstation.core.NapCatConfigService;
import com.botstation.security.AdminGate;
import com.botstation.ui.DesignTokens;
import com.mcz.AnnouncementEditor;
import com.mcz.MczEmbedBridge;
import com.mcz.MczTool;
import com.mybot.SongBot;
import com.mybot.WebsitePostBridge;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import javax.swing.JComponent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

final class SelfTest {
    private SelfTest() {}

    static int run(BotPaths paths) {
        int failures = 0;
        failures += check("SongBot class", SongBot.class != null);
        failures += check("MczTool class", MczTool.class != null);
        failures += check("AnnouncementEditor class", AnnouncementEditor.class != null);
        failures += check("Admin capability boundary", validateAdminBoundary(paths));
        failures += check("Cloud API authorization boundary", validateCloudAuthorization(paths));
        failures += check("Trusted IP privacy boundary", validateTrustedIpBoundary(paths));
        failures += check("Multilingual UI font fallback", validateFontFallback());
        failures += check("Announcement templates", validateAnnouncementTemplates());
        failures += check("Embedded MCZ contract", validateMczEmbedContract());
        failures += check("SongBot directory", Files.isDirectory(paths.songBot));
        failures += check("MczMaker directory", Files.isDirectory(paths.mczMaker));
        failures += check("NapCat launcher", Files.isRegularFile(paths.napCat.resolve("napcat.bat")));
        failures += check("NapCat local connection config", validateNapCatConfig(paths));
        failures += check("Funnel-free public routing", validateFunnelFreeRouting(paths));
        failures += check("Song database", validateDatabase(paths));
        failures += check("Stable workbook", validateWorkbook(paths));
        System.out.println(failures == 0 ? "SELF_TEST_OK" : "SELF_TEST_FAILED=" + failures);
        return failures == 0 ? 0 : 2;
    }

    private static boolean validateDatabase(BotPaths paths) {
        if (!Files.isRegularFile(paths.songDatabase)) return false;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.songDatabase);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1 FROM songs LIMIT 1")) {
            return true;
        } catch (Exception error) { error.printStackTrace(); return false; }
    }

    private static boolean validateWorkbook(BotPaths paths) {
        if (!Files.isRegularFile(paths.stableWorkbook)) return false;
        try (java.io.InputStream input = Files.newInputStream(paths.stableWorkbook);
             org.apache.poi.ss.usermodel.Workbook workbook = WorkbookFactory.create(input)) {
            return workbook.getNumberOfSheets() > 0;
        } catch (Exception error) { error.printStackTrace(); return false; }
    }

    private static boolean validateAdminBoundary(BotPaths paths) {
        try {
            for (Constructor<?> constructor : AdminGate.AdminSession.class.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers())) return false;
            }
            if (!requiresSession(Class.forName("com.botstation.ui.AnnouncementPanel"))) return false;
            if (!requiresSession(Class.forName("com.botstation.ui.WebsiteContentPanel"))) return false;
            if (!requiresSession(WebsitePostBridge.class)) return false;
            for (Constructor<?> constructor : WebsitePostBridge.class.getConstructors()) {
                if (constructor.getParameterCount() == 1) return false;
            }
            return true;
        } catch (Exception error) { error.printStackTrace(); return false; }
    }

    private static boolean requiresSession(Class<?> type) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                if (parameter == AdminGate.AdminSession.class) return true;
            }
        }
        return false;
    }

    private static boolean validateCloudAuthorization(BotPaths paths) {
        try {
            String api = Files.readString(paths.songBot.resolve("deploy/api/announcement-cloud.js"), StandardCharsets.UTF_8);
            String security = Files.readString(paths.songBot.resolve("deploy/api/_lib/security.js"), StandardCharsets.UTF_8);
            String repository = Files.readString(paths.songBot.resolve("deploy/api/_lib/repository.js"), StandardCharsets.UTF_8);
            return api.contains("admin authorization required")
                && api.contains("action.startsWith('website-')")
                && api.contains("repo.deviceAllowed")
                && security.contains("sessionFromRequest")
                && security.contains("desktopAuthorized")
                && repository.contains("security/admin-devices.json");
        } catch (Exception error) { error.printStackTrace(); return false; }
    }

    private static boolean validateTrustedIpBoundary(BotPaths paths) {
        try {
            String api = Files.readString(paths.songBot.resolve("deploy/api/announcement-cloud.js"), StandardCharsets.UTF_8);
            String security = Files.readString(paths.songBot.resolve("deploy/api/_lib/security.js"), StandardCharsets.UTF_8);
            String repository = Files.readString(paths.songBot.resolve("deploy/api/_lib/repository.js"), StandardCharsets.UTF_8);
            Class.forName("com.botstation.security.AdminIpTrustClient");
            return api.contains("desktop-ip-check") && api.contains("desktop-ip-grant")
                && api.contains("workstation-admin-check") && api.contains("workstation-admin-grant")
                && security.contains("x-vercel-forwarded-for") && security.contains("ipFingerprint")
                && repository.contains("security/admin-ips.json") && repository.contains("trustedIpAllowed");
        } catch (Exception error) { error.printStackTrace(); return false; }
    }

    private static boolean validateFontFallback() {
        String sample = "中文 日本語 한국어";
        return DesignTokens.BODY.canDisplayUpTo(sample) < 0
            && DesignTokens.forText("Bot 工作站", DesignTokens.BRAND).canDisplayUpTo("Bot 工作站") < 0;
    }

    private static boolean validateFunnelFreeRouting(BotPaths paths) {
        try {
            String routing = Files.readString(paths.songBot.resolve("deploy/vercel.json"), StandardCharsets.UTF_8);
            String core = Files.readString(paths.songBot.resolve("deploy/assets/js/core.js"), StandardCharsets.UTF_8);
            return !routing.contains("tailae715d.ts.net")
                && !routing.contains("/api/visit\"")
                && !core.contains("tailae715d.ts.net")
                && core.contains("var API_BASE = ''");
        } catch (Exception error) { error.printStackTrace(); return false; }
    }

    private static boolean validateNapCatConfig(BotPaths paths) {
        try {
            NapCatConfigService.Snapshot value = new NapCatConfigService(paths).load();
            return value.webUiPort >= 1024 && value.httpPort >= 1024
                && value.webUiPort != value.httpPort
                && ("127.0.0.1".equals(value.webUiHost) || "localhost".equalsIgnoreCase(value.webUiHost))
                && ("127.0.0.1".equals(value.httpHost) || "localhost".equalsIgnoreCase(value.httpHost))
                && (!value.callbackEnabled || value.callbackUrl.startsWith("http://127.0.0.1:"));
        } catch (Exception error) { error.printStackTrace(); return false; }
    }

    private static boolean validateAnnouncementTemplates() {
        try {
            Field field = AnnouncementEditor.class.getDeclaredField("CONTENT_TEMPLATES");
            field.setAccessible(true);
            String[][] values = (String[][]) field.get(null);
            String[] expected = {"专辑模板", "Event模板", "单曲模板", "观影封面", "观影定位"};
            if (values.length != expected.length) return false;
            for (int i = 0; i < expected.length; i++) {
                if (values[i].length < 2 || !expected[i].equals(values[i][0]) || values[i][1].isEmpty()) return false;
            }
            return true;
        } catch (Exception error) { error.printStackTrace(); return false; }
    }

    private static boolean validateMczEmbedContract() {
        try {
            Method method = MczEmbedBridge.class.getMethod("create", java.nio.file.Path.class);
            return JComponent.class.isAssignableFrom(method.getReturnType());
        } catch (Exception error) { error.printStackTrace(); return false; }
    }

    private static int check(String label, boolean okay) {
        System.out.println((okay ? "[OK] " : "[FAIL] ") + label);
        return okay ? 0 : 1;
    }
}
