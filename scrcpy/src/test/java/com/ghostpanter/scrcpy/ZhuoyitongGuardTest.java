package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ZhuoyitongGuardTest {

    private static ZhuoyitongGuard.Inputs in() {
        return new ZhuoyitongGuard.Inputs();
    }

    @Test public void plainAndroidPasses() {
        ZhuoyitongGuard.Inputs i = in();
        i.kernel = "Linux version 5.10.198-android12-9 (build-user@build-host) #1 SMP PREEMPT";
        i.cgroup = "0::/uid_10234/pid_1234\n";
        i.props = "[ro.build.fingerprint]: [google/oriole/oriole:14/AP1A/123:user/release-keys]\n"
                + "[ro.build.characteristics]: [nosdcard]\n[ro.product.name]: [android_x86]\n";
        i.build = "android\nHUAWEI\n";
        assertFalse(ZhuoyitongGuard.evaluate(i).detected());
    }

    @Test public void huaweiHarmony4Passes() {
        ZhuoyitongGuard.Inputs i = in();
        i.kernel = "Linux version 5.10.43 (HwBuild@WRGHUB) #1 SMP PREEMPT";
        i.props = "[hw_sc.build.platform.version]: [4.2.0]\n[ro.build.ohos.devicetype]: [phone]\n"
                + "[ro.build.characteristics]: [default]\n[ro.product.brand]: [HUAWEI]\n";
        assertFalse(ZhuoyitongGuard.evaluate(i).detected());
    }

    @Test public void waydroidLxcAlonePasses() {
        ZhuoyitongGuard.Inputs i = in();
        i.cgroup = "0::/lxc/waydroid\n";
        i.props = "[ro.product.name]: [waydroid_x86_64]\n";
        assertFalse(ZhuoyitongGuard.evaluate(i).detected());
    }

    @Test public void hongmengKernelBlocks() {
        ZhuoyitongGuard.Inputs i = in();
        i.kernel = "HongMeng Kernel 1.6.0 aarch64";
        assertTrue(ZhuoyitongGuard.evaluate(i).detected());
    }

    @Test public void isuladCgroupBlocks() {
        ZhuoyitongGuard.Inputs i = in();
        i.cgroup = "0::/isulad/4f2a9c\n";
        assertTrue(ZhuoyitongGuard.evaluate(i).detected());
    }

    @Test public void installerBlocks() {
        ZhuoyitongGuard.Inputs i = in();
        i.installer = ZhuoyitongGuard.ZYT_STORE_PKG;
        assertTrue(ZhuoyitongGuard.evaluate(i).detected());
    }

    @Test public void twoWeakBlocks() {
        ZhuoyitongGuard.Inputs i = in();
        i.cgroup = "0::/lxc/abc\n";
        i.packages = "package:com.zhuoyi.appstore.lite\n";
        assertTrue(ZhuoyitongGuard.evaluate(i).detected());
    }

    @Test public void oneWeakPasses() {
        ZhuoyitongGuard.Inputs i = in();
        i.packages = "package:com.zhuoyi.appstore.lite\n";
        assertFalse(ZhuoyitongGuard.evaluate(i).detected());
    }

    @Test public void remoteSectionsParse() {
        String out = "@@K\nHongMeng Kernel\n@@C\n0::/\n@@M\n@@P\n[a]: [b]\n@@G\n@@E\n";
        assertTrue(ZhuoyitongGuard.section(out, "@@K", "@@C").contains("HongMeng"));
    }
}
