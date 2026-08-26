package com.mybot.compat;

/**
 * SongBot /api/excel 端点使用的无界面兼容实现。
 * 该端点当前不消费 workbook；独立命名可避免与完整 MczMaker ExcelManager 冲突。
 */
public final class HeadlessExcelManager {
    private HeadlessExcelManager() {}

    public static void initExcel(Object ignoredParent) {
        // Intentionally empty: the current endpoint only acknowledges the request.
    }
}
