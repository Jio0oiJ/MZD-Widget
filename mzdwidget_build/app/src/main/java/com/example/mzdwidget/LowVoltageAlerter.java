package com.example.mzdwidget;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * v1.9.18：低电压警示窗入口。
 *
 * 车况慢刷/快刷都跑在后台工作线程，不能直接用 Application Context 弹 AlertDialog。
 * 这里把主界面拉到前台并带上电压参数，由 MainActivity 在 onCreate/onNewIntent 里弹窗。
 * 前台服务（BLE 扫描）存在时系统一般允许后台拉起；失败只写日志，不影响控车。
 */
public final class LowVoltageAlerter {

    public static final String EXTRA_VOLTAGE = "mzd_low_voltage_v";

    private LowVoltageAlerter() {}

    /** 从任意线程调用；电压单位 V，例如 11.6。 */
    public static void show(double voltage) {
        Context c = MzdApp.get();
        if (c == null) return;
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Bundle b = new Bundle();
            b.putDouble(EXTRA_VOLTAGE, voltage);
            i.putExtras(b);
            c.startActivity(i);
            CtrlLog.i("MZDCtrlLog", "【低电压】已拉起主界面警示窗，电压=" + voltage + "V");
        } catch (Throwable t) {
            CtrlLog.w("MZDCtrlLog", "【低电压】拉起警示窗失败（仍已响两声）："
                    + (t.getMessage() == null ? t.toString() : t.getMessage()));
        }
    }
}
