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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    private lateinit var root: LinearLayout
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var nfc: NfcAdapter? = null
    private var ir: ConsumerIrManager? = null
    private var nfcOn = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        nfc = NfcAdapter.getDefaultAdapter(this)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

        val sv = ScrollView(this)
        sv.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 48, 32, 48)
        sv.addView(root)
        setContentView(sv)

        text("Flipper Phone", 24f)
        text("يكتشف عتاد هاتفك ويفعّل الوحدات المتوافقة فقط", 14f)

        val hasWifi = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
        val hasBle = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val hasNfc = nfc != null
        val hasIr = ir?.hasIrEmitter() == true
        val hasUsb = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

        section("Wi-Fi", hasWifi) { button("مسح الشبكات") { wifiScan() } }
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
        section("USB Host", hasUsb) { text("مكتشف فقط، لا توجد أدوات بعد", 14f) }

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
