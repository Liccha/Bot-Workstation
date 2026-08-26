package com.mcz;

/**
 * SongBot 专用的最小化 ExcelManager 占位实现。
 *
 * 背景：原 MczMaker 项目里的 ExcelManager 强依赖 MczTool 整个 GUI（Swing / UiKit /
 * CalendarPanel / DesignCanvas 等），但 SongBot 只在 /api/excel 写入端点中以
 * `com.mcz.ExcelManager.initExcel(null)` 的形式调用过一次，而该端点当前是简化版
 * （不消费 workbook 内容，直接返回 {"ok":true}），因此这个 workbook 创建出来也从未
 * 被使用。
 *
 * 为了让 SongBot 在移植后能独立编译、不再依赖缺失的私有 Maven 包 com.mcz:MczTool，
 * 这里提供一个无副作用的占位方法。如需完整 Excel 写入能力，请恢复对 MczMaker
 * （MczTool 模块）的构建依赖，并删除本占位文件。
 */
public class ExcelManager {
    /** 占位实现：SongBot 的 Excel 写入端点为简化版，不依赖实际 workbook 内容。 */
    public static void initExcel(Object parent) {
        // no-op：原调用方传入 null 且不消费返回值，故此处不执行任何逻辑。
    }
}
