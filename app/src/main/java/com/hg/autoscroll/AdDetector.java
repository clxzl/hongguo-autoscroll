package com.hg.autoscroll;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 红果短剧广告识别器：对当前窗口做一次无障碍树遍历，产出「广告证据 + 片尾提示」快照。
 *
 * <p>识别全部基于本机可读到的节点文字 / 控件 id / class，不联网、不截图。
 * 判断依据（按优先级）：
 * <ol>
 *   <li>倒计时：「3秒后可继续上滑观看短剧」「5s 后可继续上滑观看」，含数字与文案被拆成两个节点的布局</li>
 *   <li>「广告」角标剩余秒数：「广告 15s」</li>
 *   <li>广告 SDK 控件：穿山甲（openadsdk）等广告容器的 view id / class</li>
 *   <li>直播购物类：「进入直播间」「直播中」「讲解中」「已售 N 件」</li>
 *   <li>游戏类：「点击进入游戏」「点击试玩」等按钮文案（只作识别信号，绝不点击）</li>
 *   <li>商业词组合：同屏两个商业词，或一个商业词 + 严格「广告」标签；「优选服务」「进入直播间」单词即判定</li>
 *   <li>严格「广告」标签：整段文字以「广告」开头且很短，避免「医疗器械广告审批号」这类长文案误判</li>
 * </ol>
 *
 * <p>另有两类安全信号：
 * 「倍速面板」出现时应当暂停一切自动滑动（面板里的「上滑锁定倍速」和片尾提示同形）；
 * 「跳过」按钮需排除「跳过片头」，且带倒计时数字时尚未可用，不能点。
 */
final class AdDetector {

    private AdDetector() {
    }

    /** 倒计时：数字 + 秒/分单位 + 「后…上滑/继续/观看…」 */
    private static final Pattern P_COUNTDOWN = Pattern.compile(
            "(\\d{1,3})\\s*[秒sS]\\s*后[^0-9]{0,12}?(上滑|滑动|继续|观看|跳过|关闭|自动|播放|进入|下一集|解锁|可滑)");

    /** 「广告」角标上的剩余秒数：「广告 15s」「广告 15」 */
    private static final Pattern P_CHIP_COUNTDOWN = Pattern.compile(
            "广告\\s*(\\d{1,3})\\s*[sS秒]?(?![a-zA-Z0-9])\\s*$");

    /** 拆分布局的倒计时尾部：「后可继续上滑观看」「秒后可继续上滑观看短剧」 */
    private static final Pattern P_COUNTDOWN_TAIL = Pattern.compile(
            "^(?:[秒sS]\\s*)?后[^0-9]{0,12}?(上滑|滑动|继续|观看|跳过|关闭|自动|播放|进入|下一集|解锁|可滑)");

    /** 拆分布局的倒计时数字节点：「5」「5s」「5秒」 */
    private static final Pattern P_COUNTDOWN_NUMBER = Pattern.compile("^\\s*(\\d{1,3})\\s*[sS秒]?\\s*$");

    /** 可点击的跳过按钮文案（排除片头） */
    private static final Pattern P_SKIP = Pattern.compile(
            "^(跳过|跳過|跳过广告|关闭广告|skip)[\\s|｜·]*(\\d{1,3}\\s*[sS秒]?)?$",
            Pattern.CASE_INSENSITIVE);

    /** 「跳过 5」这类未归零的跳过按钮：不可用，点了没用还刷屏 */
    private static final Pattern P_SKIP_LOCKED = Pattern.compile("跳过[^0-9]{0,4}\\d{1,3}");

    /** 片尾提示：「本集完」这类定长文案（contains 匹配） */
    private static final String[] END_KEYWORDS = {"本集完", "本集已完", "下一话"};

    /**
     * 上滑解锁 / 片尾提示：「上滑继续观看短剧」「上滑继续看短剧」「上滑观看下一集」。
     * 必须以上滑开头：锁定期的「N秒后可继续上滑观看短剧」也含「上滑观看」，靠开头是不是数字区分；
     * 「上滑查看详情」（进商城）不算，否则会点进广告详情页。
     */
    private static final Pattern P_UNLOCK_HINT = Pattern.compile(
            "^[^0-9]{0,4}上滑[^0-9]{0,4}(继续|观看|进入|下一集|看)");

    /** 倍速面板特征：出现任意一个就暂停自动滑动 */
    private static final String[] SPEED_PANEL_KEYWORDS = {"锁定倍速", "倍速快进", "快进中"};

    /** 直播推广专属词：剧集页不会出现，单词即判定 */
    private static final String[] LIVE_KEYWORDS = {"进入直播间", "点击进入直播间", "直播中", "讲解中"};

    /** 游戏类广告按钮文案：只作识别信号 */
    private static final String[] GAME_KEYWORDS = {"点击进入游戏", "点击试玩", "立即试玩", "点击游玩", "点击开玩"};

    /**
     * 通用商业行动词：同屏两个不同的词，或一个词 + 严格「广告」标签 → 判定为广告页。
     * 单词不判，避免普通内容误触。
     */
    private static final String[] COMMERCE_KEYWORDS = {
            "预约", "咨询", "查看详情", "了解详情", "点击链接", "获取更多",
            "免费领取", "立即购买", "立即抢购", "一键下单",
            "限时低价", "免费咨询", "点击下方", "优选服务", "进入直播间"
    };

    /** 单词即判定的推广卡文案：实测只出现在推广卡上 */
    private static final String[] PROMO_SINGLE_KEYWORDS = {"优选服务", "进入直播间"};

    /** 广告 SDK 控件的 view id 片段（穿山甲 / 巨量等） */
    private static final String[] AD_ID_HINTS = {
            "tt_video_ad", "tt_splash", "tt_reward", "tt_insert", "tt_full_screen",
            "splash_ad", "banner_ad", "skip_ad", "ad_container", "ad_layout"
    };

    /** 广告 SDK 的 class 名片段 */
    private static final String[] AD_CLASS_HINTS = {"openadsdk"};

    /** 跳过 / 关闭按钮的 view id 片段（按钮本身常常没有文字，只有一张图） */
    private static final String[] SKIP_ID_HINTS = {
            "skip_btn", "skip_ad", "ad_skip", "ad_close", "close_ad", "skip_layout"
    };

    /** 直播播放器控件（ttlive_player_render_view）：只作滑动几何提示，不单独当广告证据 */
    private static final String LIVE_PLAYER_ID = "ttlive_player";

    static final int KIND_NONE = 0;
    static final int KIND_COUNTDOWN = 1;
    static final int KIND_CHIP = 2;
    static final int KIND_SDK = 3;
    static final int KIND_LIVE = 4;
    static final int KIND_GAME = 5;
    static final int KIND_PROMO = 6;
    static final int KIND_LABEL = 7;

    /** 一次扫描的结果 */
    static final class Snapshot {
        /** 广告证据说明；null 表示不是广告页 */
        String reason;
        int kind = KIND_NONE;
        /** 屏幕上的剩余秒数，-1 表示没有倒计时 */
        int countdownSec = -1;
        /** 可点击的跳过按钮（已经确认可用） */
        AccessibilityNodeInfo skipNode;
        String skipText;
        /** 看到了跳过按钮但还在倒计时 / 不可点 */
        boolean skipLocked;
        /** 直播购物页：底部是可滚动商品面板，滑动起点要抬高 */
        boolean liveShop;
        /** 屏上有直播播放器控件：只影响滑动几何，不单独构成广告证据 */
        boolean livePlayer;
        /** 屏上出现倍速面板，本轮应当暂停所有自动滑动 */
        boolean speedPanel;
        /** 可见文字节点数（空白页兜底用） */
        int textCount;

        /** 片尾提示（可见） */
        String endHit;
        Rect endBounds;
        String hiddenHit;
        Rect hiddenBounds;

        /** 可见文字拼接（调试日志用） */
        String texts;

        boolean isAd() {
            return reason != null;
        }

        /** 弱证据（「广告」标签 / 只有 SDK 控件）：需要连续出现一小会儿才动手 */
        boolean isWeak() {
            return kind == KIND_LABEL || kind == KIND_SDK;
        }
    }

    static Snapshot scan(AccessibilityNodeInfo root, int screenWidth, int screenHeight) {
        Snapshot out = new Snapshot();
        if (root == null) return out;
        Walker w = new Walker(out, Math.max(1, screenWidth) * Math.max(1, screenHeight));
        w.walk(root, 0);
        w.finish();
        return out;
    }

    /** 片尾提示词（设置页展示用） */
    static String endKeywordSummary() {
        return String.join(" / ", END_KEYWORDS) + " / 上滑继续…";
    }

    /** 遍历器：单次深度优先，边收集边判定 */
    private static final class Walker {
        private static final int MAX_DEPTH = 25;
        private static final int MAX_TEXT_NODES = 900;

        private final Snapshot out;
        private final int screenArea;
        private final List<String> texts = new ArrayList<>();
        private final StringBuilder dump = new StringBuilder();

        private int countdownSec = -1;
        private boolean chipHit;
        private int chipSec = -1;
        private boolean sdkHit;
        private boolean liveHit;
        private boolean liveShopHit;
        private boolean livePlayerHit;
        private boolean gameHit;
        private boolean adLabel;
        private boolean speedPanel;
        private final List<String> commerceHits = new ArrayList<>();
        private boolean singlePromo;

        private String skipText;
        private AccessibilityNodeInfo skipNode;
        private int skipArea = -1;
        private boolean skipLocked;

        /** 拆分布局：单独的倒计时数字节点与尾部文案节点，按屏幕距离就近配对 */
        private final List<int[]> numbers = new ArrayList<>();   // {sec, cx, cy}
        private final List<int[]> tails = new ArrayList<>();     // {cx, cy}

        Walker(Snapshot out, int screenArea) {
            this.out = out;
            this.screenArea = screenArea;
        }

        void walk(AccessibilityNodeInfo node, int depth) {
            if (node == null || depth > MAX_DEPTH) return;
            if (texts.size() >= MAX_TEXT_NODES) return;

            String t = normalize(node.getText());
            String d = normalize(node.getContentDescription());
            boolean visible = node.isVisibleToUser();

            if (visible) {
                if (!t.isEmpty()) onText(node, t);
                if (!d.isEmpty() && !d.equals(t)) onText(node, d);
                checkSkipById(node);
            } else if (out.hiddenHit == null) {
                // 红果会把还没到时间的提示节点提前挂进树里（vis=false），只记日志，不算数
                String hit = matchEndKeyword(t);
                if (hit == null) hit = matchEndKeyword(d);
                if (hit != null) {
                    out.hiddenHit = hit;
                    out.hiddenBounds = new Rect();
                    node.getBoundsInScreen(out.hiddenBounds);
                }
            }
            onNodeIdentity(node);

            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                walk(node.getChild(i), depth + 1);
            }
        }

        /** 一个可见文字片段：跑全部文字特征 */
        private void onText(AccessibilityNodeInfo node, String s) {
            texts.add(s);
            if (dump.length() < 1200) dump.append(s).append(" | ");

            // 倒计时
            if (countdownSec < 0) {
                Matcher m = P_COUNTDOWN.matcher(s);
                if (m.find()) {
                    countdownSec = clampSeconds(m.group(1));
                }
            }
            // 「广告 15s」角标
            if (!chipHit) {
                Matcher m = P_CHIP_COUNTDOWN.matcher(s);
                if (m.find()) {
                    chipHit = true;
                    int sec = clampSeconds(m.group(1));
                    if (sec > 0 && (chipSec < 0 || sec < chipSec)) chipSec = sec;
                }
            }
            // 拆分布局：单独的数字 / 单独的尾部文案
            if (countdownSec < 0) {
                Matcher num = P_COUNTDOWN_NUMBER.matcher(s);
                if (num.matches()) {
                    int sec = clampSeconds(num.group(1));
                    if (sec >= 1 && sec <= 90) numbers.add(center(node, sec));
                } else if (P_COUNTDOWN_TAIL.matcher(s).find()) {
                    Rect b = new Rect();
                    node.getBoundsInScreen(b);
                    tails.add(new int[]{b.centerX(), b.centerY()});
                }
            }
            // 倍速面板
            if (!speedPanel) {
                for (String k : SPEED_PANEL_KEYWORDS) {
                    if (s.contains(k)) {
                        speedPanel = true;
                        break;
                    }
                }
            }
            // 严格「广告」标签
            if (!adLabel && isStrictAdLabel(s)) adLabel = true;
            // 直播 / 游戏 / 商业词
            if (!liveHit) liveHit = containsAny(s, LIVE_KEYWORDS);
            if (liveHit && (s.contains("直播") || s.contains("讲解") || s.contains("已售"))) liveShopHit = true;
            if (!gameHit) gameHit = containsAny(s, GAME_KEYWORDS);
            if (!singlePromo) singlePromo = containsAny(s, PROMO_SINGLE_KEYWORDS);
            for (String k : COMMERCE_KEYWORDS) {
                if (s.contains(k) && !commerceHits.contains(k)) {
                    commerceHits.add(k);
                    break;
                }
            }
            // 跳过按钮
            checkSkip(node, s);

            // 片尾提示（可见）
            if (out.endHit == null) {
                String hit = matchEndKeyword(s);
                if (hit != null) {
                    out.endHit = hit;
                    out.endBounds = new Rect();
                    node.getBoundsInScreen(out.endBounds);
                }
            }
        }

        /** 与文字无关的节点身份特征：广告 SDK 的 id / class */
        private void onNodeIdentity(AccessibilityNodeInfo node) {
            CharSequence rawId = node.getViewIdResourceName();
            String name = rawId == null ? null : rawId.toString().toLowerCase(Locale.ROOT);
            if (name != null && !livePlayerHit && name.contains(LIVE_PLAYER_ID)) {
                livePlayerHit = true;
            }
            if (sdkHit) return;
            if (name != null) {
                int slash = name.lastIndexOf('/');
                String leaf = slash >= 0 ? name.substring(slash + 1) : name;
                for (String h : AD_ID_HINTS) {
                    if (name.contains(h)) {
                        sdkHit = true;
                        return;
                    }
                }
                // 只认真正像广告容器的 id 叶子名，避免 add_btn / address 这类误伤
                if (leaf.equals("ad") || leaf.startsWith("ad_") || leaf.startsWith("ad-")
                        || leaf.contains("_ad_") || leaf.endsWith("_ad")
                        || leaf.contains("advert")) {
                    sdkHit = true;
                    return;
                }
            }
            CharSequence cls = node.getClassName();
            if (cls != null) {
                String c = cls.toString().toLowerCase(Locale.ROOT);
                for (String h : AD_CLASS_HINTS) {
                    if (c.contains(h)) {
                        sdkHit = true;
                        return;
                    }
                }
            }
        }

        private void checkSkip(AccessibilityNodeInfo node, String text) {
            if (text.contains("片头")) return;                 // 「跳过片头」是播放器功能，不能点
            if (!P_SKIP.matcher(text).matches()) return;
            boolean clickable = node.isClickable() || climbClickable(node, 4) != null;
            if (!clickable) return;
            if (P_SKIP_LOCKED.matcher(text).find() || !node.isEnabled()) {
                skipLocked = true;
                return;
            }
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            int area = b.width() * b.height();
            if (area <= 0) return;
            // 同一页可能有多个「跳过」：取面积最小的那个（广告角标旁的跳过键比整块浮层小）
            if (skipArea >= 0 && area >= skipArea) return;
            AccessibilityNodeInfo target = node.isClickable() ? node : climbClickable(node, 4);
            if (target == null || !fitClickArea(target)) return;
            skipArea = area;
            skipNode = target;
            skipText = text;
        }

        /**
         * 没有文字的跳过按钮：开屏广告的「跳过」常常是一张图 / 一个自定义 View，
         * 只能靠 view id 认（穿山甲是 com.byted.pangle:id/tt_splash_skip_btn）。
         */
        private void checkSkipById(AccessibilityNodeInfo node) {
            CharSequence raw = node.getViewIdResourceName();
            if (raw == null) return;
            String name = raw.toString().toLowerCase(Locale.ROOT);
            boolean hit = false;
            for (String h : SKIP_ID_HINTS) {
                if (name.contains(h)) {
                    hit = true;
                    break;
                }
            }
            if (!hit || !node.isEnabled()) return;
            AccessibilityNodeInfo target = node.isClickable() ? node : climbClickable(node, 3);
            if (target == null || !fitClickArea(target)) return;
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            int area = b.width() * b.height();
            if (area <= 0) return;
            if (skipArea >= 0 && area >= skipArea) return;
            skipArea = area;
            skipNode = target;
            skipText = leafOf(name);
        }

        /**
         * 可点控件不能太大：占屏超过 40% 的多半是列表 / 整页浮层容器，
         * 点它的中心会落到内容上（用户看到的是「一进页面就乱跳」）。
         */
        private boolean fitClickArea(AccessibilityNodeInfo node) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            long area = (long) b.width() * b.height();
            return area > 0 && area * 100 / screenArea <= 40;
        }

        private static String leafOf(String viewId) {
            int slash = viewId.lastIndexOf('/');
            String leaf = slash >= 0 ? viewId.substring(slash + 1) : viewId;
            return "[id]" + leaf;
        }

        private void finish() {
            out.textCount = texts.size();
            out.speedPanel = speedPanel;
            out.skipLocked = skipLocked;
            out.skipNode = skipNode;
            out.skipText = skipText;
            out.livePlayer = livePlayerHit;
            out.texts = dump.toString();

            if (countdownSec < 0) countdownSec = pairSplitCountdown();
            if (countdownSec < 0 && chipSec > 0) countdownSec = chipSec;
            if (countdownSec > 0) out.countdownSec = countdownSec;

            // 判定优先级：倒计时 > 角标 > SDK 控件 > 直播 > 游戏 > 商业词 > 广告标签
            if (countdownSec > 0) {
                out.kind = KIND_COUNTDOWN;
                out.reason = "countdown " + countdownSec + "s";
            } else if (chipHit) {
                out.kind = KIND_CHIP;
                out.reason = "ad chip";
            } else if (sdkHit) {
                out.kind = KIND_SDK;
                out.reason = "ad sdk view";
            } else if (liveHit) {
                out.kind = KIND_LIVE;
                out.reason = "live promo";
            } else if (gameHit) {
                out.kind = KIND_GAME;
                out.reason = "game promo";
            } else if (singlePromo) {
                out.kind = KIND_PROMO;
                out.reason = "promo card";
            } else if (commerceHits.size() >= 2) {
                out.kind = KIND_PROMO;
                out.reason = "commerce " + commerceHits.get(0) + "+" + commerceHits.get(1);
            } else if (commerceHits.size() == 1 && adLabel) {
                out.kind = KIND_PROMO;
                out.reason = "commerce " + commerceHits.get(0) + "+ad label";
            } else if (adLabel) {
                out.kind = KIND_LABEL;
                out.reason = "ad label";
            } else {
                out.kind = KIND_NONE;
                out.reason = null;
            }
            out.liveShop = liveShopHit;

            // 有跳过按钮但没判定成广告（例如播放器「跳过片头」已被排除）时，不额外造广告信号
            if (out.kind == KIND_NONE && skipNode != null) {
                out.kind = KIND_PROMO;
                out.reason = "skip button";
            }
        }

        /** 拆分布局配对：数字节点与尾部文案节点中心距离最近且在 900px 内 */
        private int pairSplitCountdown() {
            if (numbers.isEmpty() || tails.isEmpty()) return -1;
            int best = -1;
            long bestDist = Long.MAX_VALUE;
            for (int[] tail : tails) {
                for (int[] num : numbers) {
                    long dx = num[1] - tail[0];
                    long dy = num[2] - tail[1];
                    long dist = dx * dx + dy * dy;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = num[0];
                    }
                }
            }
            return bestDist <= 900L * 900L ? best : -1;
        }

        private static int[] center(AccessibilityNodeInfo node, int sec) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            return new int[]{sec, b.centerX(), b.centerY()};
        }

        private static int clampSeconds(String raw) {
            try {
                int v = Integer.parseInt(raw);
                if (v <= 0) return -1;
                return Math.min(v, 90);   // 广告倒计时不会超过 90 秒，更大的数字多半是别的东西
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private static boolean isStrictAdLabel(String s) {
            if (!s.startsWith("广告")) return false;
            // 只认「广告」「广告 15s」「广告 | 了解更多」这类短角标，
            // 「本广告由绿源电动车提供」这类长文案不算
            return s.length() <= 12;
        }

        private static boolean containsAny(String s, String[] keys) {
            for (String k : keys) {
                if (s.contains(k)) return true;
            }
            return false;
        }

        /**
         * 片尾 / 解锁提示：「本集完」这类定长文案，以及「上滑继续…」前缀。
         * 前缀匹配是必要的：真机上同时存在「上滑继续观看短剧」和「上滑继续看短剧」两种写法。
         */
        private static String matchEndKeyword(String s) {
            for (String k : END_KEYWORDS) {
                if (s.contains(k)) return k;
            }
            Matcher m = P_UNLOCK_HINT.matcher(s);
            if (m.find()) return m.group();
            return null;
        }

        private static AccessibilityNodeInfo climbClickable(AccessibilityNodeInfo node, int maxUp) {
            AccessibilityNodeInfo cur = node;
            for (int i = 0; i <= maxUp && cur != null; i++) {
                if (cur.isClickable()) return cur;
                cur = cur.getParent();
            }
            return null;
        }

        /** 去零宽字符 / 不可见分隔符，压缩空白：广告 SDK 会在文字里插指纹字符 */
        static String normalize(CharSequence cs) {
            if (cs == null) return "";
            String s = cs.toString();
            if (s.isEmpty()) return "";
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u200E'
                        || c == '\u200F' || c == '\uFEFF' || c == '\u00AD') {
                    continue;
                }
                if (c == '\n' || c == '\r' || c == '\t') {
                    sb.append(' ');
                    continue;
                }
                sb.append(c);
            }
            String out = sb.toString().trim();
            while (out.contains("  ")) out = out.replace("  ", " ");
            return out;
        }
    }
}
