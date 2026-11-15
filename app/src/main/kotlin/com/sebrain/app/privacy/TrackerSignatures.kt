package com.sebrain.app.privacy

/** A known tracking or advertising SDK, found by the Java package names its classes use. */
class Tracker(val name: String, val category: Category, val packages: List<String>) {
    enum class Category { ANALYTICS, ADVERTISING, CRASH_REPORTING, ATTRIBUTION, MESSAGING, PROFILING }
}

/**
 * Package prefixes of common trackers, based on the public code signatures of Exodus Privacy
 * (exodus-privacy.eu.org). The list is bundled so the scan works without a network request.
 * An app that renames (obfuscates) a tracker's packages is not detected.
 */
object TrackerSignatures {
    private val A = Tracker.Category.ANALYTICS
    private val D = Tracker.Category.ADVERTISING
    private val C = Tracker.Category.CRASH_REPORTING
    private val T = Tracker.Category.ATTRIBUTION
    private val M = Tracker.Category.MESSAGING
    private val P = Tracker.Category.PROFILING

    val all: List<Tracker> = listOf(
        Tracker("Google Firebase Analytics", A, listOf("com.google.firebase.analytics", "com.google.android.gms.measurement")),
        Tracker("Google Analytics", A, listOf("com.google.android.gms.analytics", "com.google.analytics")),
        Tracker("Google Tag Manager", A, listOf("com.google.android.gms.tagmanager", "com.google.tagmanager")),
        Tracker("Google AdMob", D, listOf("com.google.android.gms.ads", "com.google.ads")),
        Tracker("Google CrashLytics", C, listOf("com.crashlytics", "com.google.firebase.crashlytics", "io.fabric.sdk")),
        Tracker("Facebook Analytics", A, listOf("com.facebook.appevents")),
        Tracker("Facebook Ads", D, listOf("com.facebook.ads")),
        Tracker("Facebook Login", P, listOf("com.facebook.login")),
        Tracker("Facebook Share", P, listOf("com.facebook.share")),
        Tracker("AppsFlyer", T, listOf("com.appsflyer")),
        Tracker("Adjust", T, listOf("com.adjust.sdk")),
        Tracker("Branch", T, listOf("io.branch")),
        Tracker("Kochava", T, listOf("com.kochava")),
        Tracker("Singular", T, listOf("com.singular.sdk")),
        Tracker("Amplitude", A, listOf("com.amplitude")),
        Tracker("Mixpanel", A, listOf("com.mixpanel")),
        Tracker("Segment", A, listOf("com.segment.analytics")),
        Tracker("Flurry", A, listOf("com.flurry")),
        Tracker("Heap", A, listOf("com.heapanalytics")),
        Tracker("Yandex AppMetrica", A, listOf("com.yandex.metrica", "io.appmetrica")),
        Tracker("Huawei Analytics", A, listOf("com.huawei.hms.analytics")),
        Tracker("Microsoft App Center", A, listOf("com.microsoft.appcenter")),
        Tracker("Microsoft Clarity", P, listOf("com.microsoft.clarity")),
        Tracker("Countly", A, listOf("ly.count.android")),
        Tracker("Localytics", A, listOf("com.localytics")),
        Tracker("Leanplum", A, listOf("com.leanplum")),
        Tracker("Braze", M, listOf("com.appboy", "com.braze")),
        Tracker("CleverTap", M, listOf("com.clevertap")),
        Tracker("OneSignal", M, listOf("com.onesignal")),
        Tracker("Airship", M, listOf("com.urbanairship")),
        Tracker("Iterable", M, listOf("com.iterable")),
        Tracker("Sentry", C, listOf("io.sentry")),
        Tracker("Bugsnag", C, listOf("com.bugsnag")),
        Tracker("Instabug", C, listOf("com.instabug")),
        Tracker("New Relic", C, listOf("com.newrelic.agent")),
        Tracker("Datadog", C, listOf("com.datadog.android")),
        Tracker("Dynatrace", C, listOf("com.dynatrace")),
        Tracker("UXCam", P, listOf("com.uxcam")),
        Tracker("Smartlook", P, listOf("com.smartlook")),
        Tracker("FullStory", P, listOf("com.fullstory")),
        Tracker("AppLovin", D, listOf("com.applovin")),
        Tracker("Unity Ads", D, listOf("com.unity3d.ads", "com.unity3d.services")),
        Tracker("ironSource", D, listOf("com.ironsource")),
        Tracker("Chartboost", D, listOf("com.chartboost")),
        Tracker("Liftoff Vungle", D, listOf("com.vungle")),
        Tracker("InMobi", D, listOf("com.inmobi")),
        Tracker("Mintegral", D, listOf("com.mbridge.msdk", "com.mintegral")),
        Tracker("Pangle (ByteDance)", D, listOf("com.bytedance.sdk", "com.pgl.sys")),
        Tracker("MoPub", D, listOf("com.mopub")),
        Tracker("Tapjoy", D, listOf("com.tapjoy")),
        Tracker("Taboola", D, listOf("com.taboola")),
        Tracker("Criteo", D, listOf("com.criteo")),
        Tracker("Smaato", D, listOf("com.smaato")),
        Tracker("Amazon Advertisement", D, listOf("com.amazon.device.ads")),
        Tracker("Verizon / Yahoo Ads", D, listOf("com.flurry.android.ads", "com.verizon.ads", "com.yahoo.ads")),
        Tracker("Moat", P, listOf("com.moat.analytics")),
        Tracker("Integral Ad Science", P, listOf("com.integralads")),
        Tracker("comScore", P, listOf("com.comscore")),
        Tracker("Nielsen", P, listOf("com.nielsen")),
        Tracker("Foursquare Pilgrim", P, listOf("com.foursquare.pilgrim")),
        Tracker("Radar", P, listOf("io.radar.sdk")),
        Tracker("Matomo", A, listOf("org.matomo.sdk", "org.piwik.sdk")),
    )
}
