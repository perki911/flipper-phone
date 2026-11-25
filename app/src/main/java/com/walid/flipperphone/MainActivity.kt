package com.walid.flipperphone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    private lateinit var root: LinearLayout
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var nfc: NfcAdapter? = null
    private var ir: ConsumerIrManager? = null
    private var nfcOn = false

    private val shizukuPermCode = 9001
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == shizukuPermCode) {
            ui.post {
                log(if (grantResult == PackageManager.PERMISSION_GRANTED) "تم منح صلاحية Shizuku"
                    else "تم رفض صلاحية Shizuku")
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        nfc = NfcAdapter.getDefaultAdapter(this)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        Shizuku.addRequestPermissionResultListener(shizukuListener)

        val sv = ScrollView(this)
        sv.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 48, 32, 48)
        sv.addView(root)
        setContentView(sv)

        text("Flipper Phone", 24f)
        text("يكتشف عتاد هاتفك ويفعّل الوحدات المتوافقة فقط", 14f)

        text("Shizuku", 18f)
        button("تفعيل / طلب صلاحية Shizuku") { shizukuSetup() }

        val hasWifi = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
        val hasBle = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val hasNfc = nfc != null
        val hasIr = ir?.hasIrEmitter() == true
        val hasUsb = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

        section("Wi-Fi", hasWifi) {
            button("مسح الشبكات") { wifiScan() }
            button("مسح متقدم (BSSID حقيقي عبر Shizuku)") { wifiScanShizuku() }
        }
        section("Bluetooth LE", hasBle) { button("مسح الأجهزة القريبة") { bleScan() } }
        section("NFC", hasNfc) { button("تشغيل/إيقاف قراءة البطاقات") { nfcToggle() } }
        section("IR (إرسال)", hasIr) {
            text("Samsung TV", 16f)
            button("تشغيل/إيقاف") { sendIr(samsung(0x02)) }
            button("صوت +") { sendIr(samsung(0x07)) }
            button("صوت -") { sendIr(samsung(0x0B)) }
            button("كتم") { sendIr(samsung(0x0F)) }
            text("LG TV", 16f)
            button("تشغيل/إيقاف") { sendIr(nec(0x04, 0x08)) }
            button("صوت +") { sendIr(nec(0x04, 0x02)) }
            button("صوت -") { sendIr(nec(0x04, 0x03)) }
            button("كتم") { sendIr(nec(0x04, 0x09)) }
        }
        section("USB Host", hasUsb) {
            button("فحص الأجهزة الموصولة") { usbScan() }
        }

        text("النتيجة:", 16f)
        logView = TextView(this)
        logView.textSize = 14f
        logView.setTextIsSelectable(true)
        root.addView(logView)
    }

    override fun onPause() {
        if (nfcOn) { nfc?.disableReaderMode(this); nfcOn = false }
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onDestroy()
    }

    // ---------- Shizuku ----------
    private fun shizukuSetup() {
        if (!Shizuku.pingBinder()) {
            log("تطبيق Shizuku غير مشغَّل. افتحه وفعّل الخدمة أولاً (عبر ADB أو الروت)")
            return
        }
        if (Shizuku.isPreV11()) {
            log("نسخة Shizuku قديمة جداً، حدّثها")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            log("الصلاحية ممنوحة مسبقاً")
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            log("رفضتَ الصلاحية سابقاً بشكل دائم، فعّلها يدوياً من تطبيق Shizuku")
            return
        }
        Shizuku.requestPermission(shizukuPermCode)
    }

    private fun shizukuReady(): Boolean {
        if (!Shizuku.pingBinder()) { log("شغّل تطبيق Shizuku أولاً"); return false }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            log("اضغط زر تفعيل Shizuku أولاً وامنح الصلاحية"); return false
        }
        return true
    }

    // ينفّذ أمر shell عبر عملية Shizuku (مستوى صلاحية adb shell، وليس روت كاملاً)
    private fun shizukuExec(cmd: String): String {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val err = BufferedReader(InputStreamReader(process.errorStream)).readText()
            process.waitFor()
            if (out.isNotBlank()) out else if (err.isNotBlank()) "خطأ: $err" else "(لا نتيجة)"
        } catch (e: Exception) {
            "فشل التنفيذ عبر Shizuku: " + e.message
        }
    }

    private fun wifiScanShizuku() {
        if (!shizukuReady()) return
        log("جاري الجلب عبر Shizuku...")
        Thread {
            val raw = shizukuExec("dumpsys wifi | grep -iE 'bssid|ssid|level|freq' | head -n 120")
            ui.post { log(if (raw.isBlank()) "لا نتيجة، تأكد من اتصال Wi-Fi" else raw) }
        }.start()
    }

    // ---------- UI helpers ----------
    private fun text(s: String, size: Float) {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setPadding(0, 16, 0, 8)
        root.addView(t)
    }

    private fun button(s: String, onClick: () -> Unit) {
        val bt = Button(this)
        bt.text = s
        bt.setOnClickListener { onClick() }
        root.addView(bt)
    }

    private fun section(name: String, ok: Boolean, content: () -> Unit) {
        text((if (ok) "[متوفر] " else "[غير متوفر] ") + name, 18f)
        if (ok) content()
    }

    private fun log(s: String) { logView.text = s }

    // ---------- Permissions ----------
    private fun ensurePerms(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), 1)
        log("وافق على الصلاحيات ثم اضغط الزر مرة أخرى")
        return false
    }

    // ---------- Wi-Fi ----------
    private fun wifiScan() {
        if (!ensurePerms()) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) { log("شغّل الواي فاي"); return }
        wm.startScan()
        log("جاري المسح...")
        ui.postDelayed({
            try {
                val r = wm.scanResults.sortedByDescending { it.level }
                if (r.isEmpty()) {
                    log("لا نتائج. تأكد أن الموقع (GPS) مفعّل")
                } else {
                    log(r.joinToString("\n") {
                        val n = if (it.SSID.isNullOrEmpty()) "(مخفية)" else it.SSID
                        n + " | " + it.level + "dBm | " + it.capabilities
                    })
                }
            } catch (e: SecurityException) { log("لا توجد صلاحية") }
        }, 3500)
    }

    // ---------- Bluetooth LE ----------
    private fun bleScan() {
        if (!ensurePerms()) return
        val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (ad == null || !ad.isEnabled) { log("شغّل البلوتوث"); return }
        val sc = ad.bluetoothLeScanner
        if (sc == null) { log("المسح غير متاح"); return }
        val found = LinkedHashMap<String, String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val n = r.device.name ?: "؟"
                found[r.device.address] = n + " | " + r.rssi + "dBm"
            }
        }
        sc.startScan(cb)
        log("مسح لمدة 8 ثوان...")
        ui.postDelayed({
            sc.stopScan(cb)
            log("وُجد " + found.size + " جهاز:\n" +
                found.entries.joinToString("\n") { it.key + "  " + it.value })
        }, 8000)
    }

    // ---------- NFC ----------
    private fun nfcToggle() {
        val a = nfc ?: return
        if (!a.isEnabled) { log("فعّل NFC من الإعدادات"); return }
        if (nfcOn) {
            a.disableReaderMode(this)
            nfcOn = false
            log("أُوقفت القراءة")
        } else {
            a.enableReaderMode(
                this, this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null
            )
            nfcOn = true
            log("قرّب البطاقة من ظهر الهاتف...")
        }
    }

    override fun onTagDiscovered(tag: Tag) {
        val sb = StringBuilder()
        sb.append("UID: ").append(tag.id.joinToString(":") { "%02X".format(it) })
        sb.append("\nالتقنيات: ").append(tag.techList.joinToString { it.substringAfterLast('.') })
        try {
            val n = Ndef.get(tag)
            if (n != null) {
                n.connect()
                n.ndefMessage?.records?.forEach { sb.append("\nNDEF: ").append(String(it.payload)) }
                n.close()
            }
        } catch (e: Exception) { }
        runOnUiThread { log(sb.toString()) }
    }

    // ---------- USB ----------
    // قاعدة بيانات أسماء شرائح USB معروفة (VID/PID) + ملاحظة عامة، بدون أي رابط تنزيل تلقائي.
    // المصدر: معرّفات USB العامة المنشورة (usb.org / linux-usb.org)، للعرض فقط.
    private data class ChipInfo(val name: String, val note: String)

    private val usbChipDb = mapOf(
        "0bda:8812" to ChipInfo("Realtek RTL8812AU", "شائع، يحتاج تعريف خارج الشجرة (aircrack-ng/rtl8812au) مبني لنواة هاتفك"),
        "0bda:8813" to ChipInfo("Realtek RTL8812BU", "نسخة أحدث من 8812AU، نفس ملاحظة التعريف"),
        "0bda:a81a" to ChipInfo("Realtek RTL8812AU (طراز آخر)", "نفس عائلة 8812AU"),
        "0bda:8178" to ChipInfo("Realtek RTL8192CU", "دعم monitor mode محدود"),
        "0cf3:9271" to ChipInfo("Atheros AR9271", "مدعوم جيداً في Kali NetHunter، يحتاج تعريف ath9k_htc"),
        "148f:7601" to ChipInfo("MediaTek/Ralink MT7601U", "دعم ضعيف لـ monitor mode"),
        "0e8d:7612" to ChipInfo("MediaTek MT7612U", "يحتاج تعريف mt76 خاص"),
        "2357:0101" to ChipInfo("TP-Link Archer T2U (Realtek)", "يعتمد على شريحة Realtek الداخلية، راجع الاسم أعلاه")
    )

    private fun usbScan() {
        val um = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = um.deviceList.values.toList()
        if (devices.isEmpty()) {
            log("لا توجد أجهزة USB موصولة. وصّل الكرت عبر كابل OTG")
            return
        }
        val sb = StringBuilder()
        for (d: UsbDevice in devices) {
            val key = "%04x:%04x".format(d.vendorId, d.productId)
            val info = usbChipDb[key]
            sb.append("VID:PID = ").append(key).append("\n")
            if (info != null) {
                sb.append("الشريحة: ").append(info.name).append("\n")
                sb.append("ملاحظة: ").append(info.note).append("\n")
            } else {
                sb.append("شريحة غير معروفة في القاعدة المحلية، ابحث عن \"").append(key).append("\" يدوياً\n")
            }
            sb.append("اسم الجهاز: ").append(d.deviceName).append("\n\n")
        }
        log(sb.toString())
    }

    // ---------- IR ----------
    private fun sendIr(p: IntArray) {
        try { ir?.transmit(38000, p); log("أُرسلت الإشارة") }
        catch (e: Exception) { log("فشل الإرسال: " + e.message) }
    }

    private fun bits(p: MutableList<Int>, bytes: IntArray, one: Int, zero: Int) {
        for (by in bytes) for (i in 0..7) {
            p.add(560)
            p.add(if (((by shr i) and 1) == 1) one else zero)
        }
    }

    private fun nec(addr: Int, cmd: Int): IntArray {
        val p = ArrayList<Int>()
        p.add(9000); p.add(4500)
        bits(p, intArrayOf(addr, addr.inv() and 0xFF, cmd, cmd.inv() and 0xFF), 1690, 560)
        p.add(560)
        return p.toIntArray()
    }

    private fun samsung(cmd: Int): IntArray {
        val p = ArrayList<Int>()
        p.add(4500); p.add(4500)
        bits(p, intArrayOf(0x07, 0x07, cmd, cmd.inv() and 0xFF), 1690, 560)
        p.add(560)
        return p.toIntArray()
    }
}
