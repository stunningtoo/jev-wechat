package com.google.android.accessibility.selecttospeak

import com.jev.probe.capture.ChatCaptureService

/**
 * The capture service, registered under a system-style class name.
 *
 * WeChat 8.0.52+ hides its node tree from third-party accessibility services.
 * The mechanism: it collects `AccessibilityManager.getEnabledAccessibilityServiceList`,
 * and for every service whose id is NOT on its whitelist it swaps node text, ids
 * and descriptions for values drawn at random from a cache of other nodes. The
 * whitelist is matched on the *class* part of `<pkg>/<class>` only — the package
 * is not verified. Measured on WeChat 8.0.80 with a plainly named service:
 * `nodes=1 ids=0 texts=0` — one root, nothing under it.
 *
 * Registering under one of the whitelisted class names makes `needUseFakeInfo()`
 * answer false, and the real tree comes through. All behaviour lives in
 * [ChatCaptureService]; only the class name differs.
 *
 * This is a deliberate, documented exception to the project's own rule that the
 * service carries Jev's package name — see cn/CHANGELOG.md v1.8.6. If WeChat
 * starts verifying the package part, this stops working and the name must go
 * back; there is no code path here to fall back on.
 */
class SelectToSpeakService : ChatCaptureService()
