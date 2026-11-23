package com.phone.adb;

import io.appium.java_client.MobileElement;
import org.openqa.selenium.By;

import java.util.List;

/**
 * 抖音在模拟器（x86 软件渲染 + ARM 兼容层）上运行搜索结果页等重页面时偶发 ANR，
 * 弹出"应用没有响应"系统对话框后整个 UI 冻结，Appium 所有 findElements 返回空。
 * 在采集循环关键点调用 {@link #recover}，检测到 ANR 弹窗时点击"等待"按钮让应用继续，
 * 避免整轮采集报废。
 */
public final class AnrGuard {
    private AnrGuard() { }

    /** ANR 系统对话框上的"等待"按钮（resource-id 稳定，android 包）。 */
    static final String ANR_WAIT_BTN = "//*[@resource-id='android:id/aerr_wait']"
            + " | //*[@clickable='true' and (@text='等待' or @content-desc='等待' or @text='Wait')]";

    /**
     * 检测并恢复 ANR 弹窗。
     *
     * @return true 表示检测到 ANR 并已点击"等待"；false 表示没有 ANR 弹窗。
     */
    public static boolean recover(io.appium.java_client.AppiumDriver<MobileElement> driver) {
        try {
            List<MobileElement> waitBtns = driver.findElements(By.xpath(ANR_WAIT_BTN));
            if (waitBtns.isEmpty()) return false;
            waitBtns.get(0).click();
            Thread.sleep(3000);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }
}
