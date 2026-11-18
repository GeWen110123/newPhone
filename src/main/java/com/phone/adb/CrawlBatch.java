package com.phone.adb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A persisted batch yielding the device to another queued task, not a failed attempt. */
public class CrawlBatch extends ArrayList<Map<String, Object>> {
    public CrawlBatch(List<Map<String, Object>> saved) { super(saved); }
}
