package ru.sensoterra.server.entity;

import java.util.Locale;

public enum BatteryType {
    ALKALINE("Alkaline", 2000, 2500),
    NI_CD("Ni-Cd", 1800, 2200),
    NI_MH("Ni-MH", 1800, 2200),
    LI_ION("Li-ion", 2700, 2900);

    private final String label;
    private final int minVoltageMv;
    private final int warningVoltageMv;

    BatteryType(String label, int minVoltageMv, int warningVoltageMv) {
        this.label = label;
        this.minVoltageMv = minVoltageMv;
        this.warningVoltageMv = warningVoltageMv;
    }

    public String getLabel() {
        return label;
    }

    public int getMinVoltageMv() {
        return minVoltageMv;
    }

    public int getWarningVoltageMv() {
        return warningVoltageMv;
    }

    public static BatteryType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return valueOf(code.trim().toUpperCase(Locale.ROOT));
    }
}
