package com.botstation.core;

public enum ServiceState {
    RUNNING("已启用"), STOPPED("未启用"), STARTING("正在启用"), STOPPING("正在停用"), DEGRADED("部分可用"), UNKNOWN("检测中");
    public final String label;
    ServiceState(String label) { this.label = label; }
}
