package com.yongyong.subtranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.yongyong.subtranslator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    // 화면에 보여줄 이름과 실제 언어 코드를 묶어놨어요. 새 언어를 추가하고 싶으면
    // 이 목록에 한 줄만 추가하면 돼요(스피너 드롭다운에 자동으로 나타나요).
    private val languageOptions = listOf(
        Prefs.LANG_CHINESE to "중국어",
        Prefs.LANG_JAPANESE to "일본어",
        Prefs.LANG_ENGLISH to "영어",
        Prefs.LANG_RUSSIAN to "러시아어",
        Prefs.LANG_GERMAN to "독일어"
    )

    // 1) "다른 앱 위에 표시" 권한 화면에서 돌아왔을 때
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkAndStart()
        }

    // 2) 마이크(오디오) 권한 결과
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) checkAndStart()
            else toast("오디오 권한을 허용해야 소리를 인식할 수 있어요.")
        }

    // 3) 알림 권한 결과 (안드로이드 13 이상)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            checkAndStart()
        }

    // 4) 화면(소리) 캡처 허용 결과 → 여기서 실제로 서비스를 시작함
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startTranslateService(result.resultCode, result.data!!)
            } else {
                toast("소리 캡처를 허용해야 번역을 시작할 수 있어요. (화면은 보지 않고 소리만 사용해요)")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, languageOptions.map { it.second }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = adapter

        val savedLang = Prefs.getLanguage(this)
        val savedIndex = languageOptions.indexOfFirst { it.first == savedLang }
        binding.spinnerLanguage.setSelection(if (savedIndex >= 0) savedIndex else 0)

        binding.btnSaveSettings.setOnClickListener {
            Prefs.setLanguage(this, selectedLanguage())
            toast("설정을 저장했어요.")
        }

        binding.btnStart.setOnClickListener {
            Prefs.setLanguage(this, selectedLanguage())
            checkAndStart()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, TranslateOverlayService::class.java))
            setRunningUi(false)
        }
    }

    override fun onResume() {
        super.onResume()
        setRunningUi(TranslateOverlayService.isRunning)
    }

    private fun selectedLanguage(): String =
        languageOptions.getOrNull(binding.spinnerLanguage.selectedItemPosition)?.first
            ?: Prefs.LANG_CHINESE

    /** 필요한 권한을 하나씩 확인하면서, 없는 게 있으면 그것부터 요청하고 다시 이 함수로 돌아와요. */
    private fun checkAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            toast("다음 화면에서 이 앱의 '다른 앱 위에 표시' 권한을 켜주세요.")
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        // 마지막 단계: 소리 캡처 허용 요청 (화면 녹화처럼 보이지만 소리만 사용해요)
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startTranslateService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, TranslateOverlayService::class.java).apply {
            putExtra(TranslateOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(TranslateOverlayService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        setRunningUi(true)
        toast("번역을 시작했어요. 이제 영상 앱으로 이동해서 재생해보세요.")
    }

    private fun setRunningUi(running: Boolean) {
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.textStatus.text = if (running)
            "상태: 실행 중 (화면 위 작은 자막창을 확인하세요)"
        else
            "상태: 대기 중"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
