package com.attius.homefrontalert

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class TOSActivity : AppCompatActivity() {

    private lateinit var tvTOS: TextView
    private lateinit var btnAccept: Button
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtext: TextView
    private lateinit var rbEnglish: RadioButton
    private lateinit var rbHebrew: RadioButton

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force Layout Direction based on Locale
        val isRtl = LocaleHelper.getLanguage(this) == "iw"
        window.decorView.layoutDirection = if (isRtl) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR

        setContentView(R.layout.activity_tos)

        tvTOS = findViewById(R.id.tvTOS)
        btnAccept = findViewById(R.id.btnAcceptTOS)
        tvTitle = findViewById(R.id.tvTOSTitle)
        tvSubtext = findViewById(R.id.tvTOSSubtext)
        rbEnglish = findViewById(R.id.rbEnglish)
        rbHebrew = findViewById(R.id.rbHebrew)
        val rgLanguage = findViewById<RadioGroup>(R.id.rgLanguage)

        // Initialize state based on current locale
        val currentLang = LocaleHelper.getLanguage(this)
        if (currentLang == "iw" || currentLang == "he") {
            rbHebrew.isChecked = true
        } else {
            rbEnglish.isChecked = true
        }

        refreshLocalizedText()

        setupLanguageSwitch()

        btnAccept.setOnClickListener {
            val sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean("tos_accepted", true).apply()
            requestPermissionsAndFinish()
        }
    }

    private fun requestPermissionsAndFinish() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.filter { 
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED 
        }

        if (missing.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // We finish anyway, the user chose to accept TOS. 
        // If they denied permissions, the service simply won't start (guarded in MainActivity).
        finish()
    }

    private fun setupLanguageSwitch() {
        val rgLanguage = findViewById<RadioGroup>(R.id.rgLanguage)
        val rbEnglish = findViewById<RadioButton>(R.id.rbEnglish)
        val rbHebrew = findViewById<RadioButton>(R.id.rbHebrew)

        rgLanguage.setOnCheckedChangeListener { group, checkedId ->
            val selectedLang = if (checkedId == R.id.rbHebrew) "iw" else "en"
            val actualLang = LocaleHelper.getLanguage(this)

            if (selectedLang != actualLang) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.lang_change_title)
                    .setMessage(R.string.lang_change_message)
                    .setPositiveButton(R.string.lang_change_confirm) { _, _ ->
                        LocaleHelper.setLocale(this, selectedLang)
                        // Restart to apply direction change
                        val intent = Intent(this, TOSActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        // Revert UI selection without triggering logic
                        group.setOnCheckedChangeListener(null)
                        if (actualLang == "iw" || actualLang == "he") {
                            rbHebrew.isChecked = true
                        } else {
                            rbEnglish.isChecked = true
                        }
                        setupLanguageSwitch() // Re-attach listener
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun refreshLocalizedText() {
        val lang = LocaleHelper.getLanguage(this)
        val isHebrew = lang == "iw" || lang == "he"
        
        val tosText = if (isHebrew) {
            """
                ברוכים הבאים ל-Home Front Alert (לא רשמי)
                
                1. מהות השירות
                אפליקציה זו היא כלי מחקרי שנוצר למטרות ניסיוניות בלבד. אין לה שום קשר לפיקוד העורף הרשמי או לכל גוף ממשלתי של מדינת ישראל. המפתח אינו מייצג את הרשויות הרשמיות.
                
                2. אין תחליף לערוצים רשמיים
                אפליקציה זו אינה מכשיר מציל חיים. היא אסורה לשימוש כתחליף לאפליקציות חירום רשמיות, צופרים, הודעות רדיו או אמצעי בטיחות רשמיים אחרים. יש לתעדף תמיד מידע רשמי מפיקוד העורף.
                
                3. אחריות "כפי שהיא" (AS IS)
                השירות מסופק על בסיס "כפי שהוא" ו-"כפי שזמין". איננו מבטיחים שהאפליקציה תפעל בצורה נכונה, בזמן או ללא הפרעות. כשלים טכניים, עיכובים בשרת או חסימות רשת עלולים להתרחש בכל עת.
                
                4. הגבלת אחריות
                במידה המקסימלית המותרת על פי חוק, למפתח ול-Attius Digital Art לא תהיה כל אחריות לכל נזק שהוא (כולל, ללא הגבלה, נזקים ישירים, עקיפים, מיוחדים או תוצאתיים) הנובעים מהשימוש או מחוסר היכולת להשתמש באפליקציה זו.
                
                5. סמכות שיפוט תקינה
                תנאי שימוש אלה כפופים לחוקי מדינת ישראל. כל מחלוקת הנובעת מתנאים אלה תובא לשיפוט בלעדי בבתי המשפט המוסמכים בעיר ירושלים בלבד.
                
                6. פרטיות
                נתוני המיקום שלך מעובדים מקומית במכשירך לצורך חישובי מרחק ואינם נשמרים בשרתים שלנו.
                
                בהמשך השימוש, הנך מאשר את התנאים הללו ומסכים להשתמש באפליקציה על אחריותך הבלעדית.
            """.trimIndent()
        } else {
            """
                WELCOME TO HOME FRONT ALERT (UNOFFICIAL)
                
                1. NATURE OF THE SERVICE
                This application is an exploratory tool created for experimental purposes. It has NO connection to the official Home Front Command (Pikud HaOref) or any governmental body of the State of Israel. The developer does not represent the official authorities.
                
                2. NO REPLACEMENT FOR OFFICIAL CHANNELS
                This app is NOT a life-saving device. It MUST NOT replace official emergency applications, broadcast sirens, radio announcements, or other official means of safety. Always prioritize official information from the Home Front Command.
                
                3. "AS IS" WARRANTY
                The service is provided on an "AS IS" and "AS AVAILABLE" basis. We do not guarantee that the app will work correctly, timely, or without interruptions. Technical failures, server delays, or networking blocks may occur at any time.
                
                4. LIMITATION OF LIABILITY
                To the maximum extent permitted by law, the developer and Attius Digital Art shall have no liability for any damages whatsoever (including, without limitation, direct, indirect, special, or consequential damages) arising out of the use or inability to use this application.
                
                5. JURISDICTION
                These Terms shall be governed by the laws of the State of Israel. Any disputes arising from these terms shall be subject to the exclusive jurisdiction of the competent courts in the city of Jerusalem, Israel.
                
                6. PRIVACY
                Your location data is processed locally on your device for distance calculations and is not stored on our servers.
                
                By proceeding, you acknowledge these conditions and agree to use the app at your own sole risk.
            """.trimIndent()
        }

        tvTOS.text = tosText
        // Force LTR for English or RTL for Hebrew on the ScrollView content if needed
        tvTOS.textDirection = if (isHebrew) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
    }
    
    override fun onBackPressed() {
        // Prevent skipping TOS by hitting back
        super.onBackPressed()
        finishAffinity() // Close app if they don't agree
    }
}
