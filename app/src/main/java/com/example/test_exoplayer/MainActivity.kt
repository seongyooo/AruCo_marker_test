package com.example.test_exoplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.test_exoplayer.databinding.ActivityMainBinding
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONObject
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var socket: Socket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // 서버에서 받은 marker 및 grid 정보
    private var myMarkerId = -1
    private var gridRows = 0 // 초기값을 0으로 변경
    private var gridCols = 0 // 초기값을 0으로 변경
    private var myRow = -1
    private var myCol = -1
    private var myRotation = 0

    // --- 레이스 컨디션 해결을 위한 변수 ---
    private var isSurfaceReady = false
    private var isLayoutReady = false
    private var surface: Surface? = null

    private val serverUrl = "http://192.168.50.24:5000" // ❗️ 실제 PC IP로 수정
    private val streamUrl = "udp://224.1.1.1:1234"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("multicastLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        setupSocket()
        setupTextureViewListener()
        hideSystemUI()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, binding.root)

        // 상태 표시줄과 내비게이션 바를 모두 숨깁니다.
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        // 사용자가 화면 가장자리를 스와이프할 때만 시스템 바가 일시적으로 나타나도록 설정합니다.
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupSocket() {
        socket = IO.socket(serverUrl)
        socket?.on(Socket.EVENT_CONNECT) {
            Log.d("Socket.IO", "서버 연결!")
            socket?.emit("client_ready")
            runOnUiThread { binding.tvStatus.text = "서버 연결됨, 대기 중..." }
        }
        socket?.on("server_command") { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                val payload = args[0] as JSONObject
                val cmd = payload.optString("command")
                when (cmd) {
                    "assign_id" -> {
                        myMarkerId = payload.getInt("marker_id")
                        runOnUiThread { binding.tvStatus.text = "내 마커ID: $myMarkerId" }
                    }
                    "show_marker" -> {
                        runOnUiThread { showMarkerUI() }
                    }
                    "start_playback" -> {
                        val layoutJson = JSONObject(payload.getString("data"))
                        setGridLayoutInfo(layoutJson)

                        // =======================================================
                        // 여기가 수정된 부분입니다 (1): 레이아웃 준비 완료 신호
                        // =======================================================
                        isLayoutReady = true
                        runOnUiThread {
                            tryStartVideo() // 비디오 시작 시도
                        }
                    }
                }
            }
        }
        socket?.connect()
    }

    private fun showMarkerUI() {
        binding.tileImageView.visibility = View.GONE
        binding.markerImageView.visibility = View.VISIBLE
        val resourceId = resources.getIdentifier("marker_$myMarkerId", "drawable", packageName)
        if (resourceId != 0) {
            binding.markerImageView.setImageResource(resourceId)
            binding.tvStatus.text = "마커 표시 중 (ID: #$myMarkerId)"
        } else {
            binding.tvStatus.text = "오류: 마커 이미지 없음 (ID: #$myMarkerId)"
        }
    }

    private fun setGridLayoutInfo(json: JSONObject) {
        val gridInfo = json.getJSONObject("grid_info")
        gridRows = gridInfo.getInt("rows")
        gridCols = gridInfo.getInt("cols")
        val layoutArray = json.getJSONArray("layout")
        for (i in 0 until layoutArray.length()) {
            val marker = layoutArray.getJSONObject(i)
            if (marker.getInt("id") == myMarkerId) {
                val gridPos = marker.getJSONObject("grid_pos")
                myRow = gridPos.getInt("row")
                myCol = gridPos.getInt("col")
                myRotation = marker.getInt("rotation")
                break
            }
        }
    }

    private fun setupTextureViewListener() {
        binding.hiddenTextureView.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    // =======================================================
                    // 여기가 수정된 부분입니다 (2): Surface 준비 완료 신호
                    // =======================================================
                    Log.d("VideoSetup", "SurfaceTexture 준비 완료.")
                    this@MainActivity.surface = Surface(st)
                    isSurfaceReady = true
                    tryStartVideo() // 비디오 시작 시도
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) : Boolean {
                    this@MainActivity.surface?.release()
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
    }

    // =======================================================
    // 여기가 수정된 부분입니다 (3): 두 조건이 모두 만족될 때만 실행되는 '중재자' 함수
    // =======================================================
    private fun tryStartVideo() {
        // 두 조건이 모두 true이고, player가 아직 실행되지 않았을 때만 실행
        if (isSurfaceReady && isLayoutReady && player == null) {
            Log.d("VideoSetup", "모든 조건 충족! 비디오 재생을 시작합니다.")

            // UI 변경
            binding.markerImageView.visibility = View.GONE
            binding.tileImageView.visibility = View.VISIBLE
            binding.tvStatus.visibility = View.GONE

            // 플레이어 실행
            startPlayerWithSurface(surface!!)
        }
    }

    private fun startPlayerWithSurface(surface: Surface) {
        player = ExoPlayer.Builder(this).build().apply {
            setVideoSurface(surface)
            setMediaItem(MediaItem.fromUri(streamUrl))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
        startCropLoop()
    }

    private var cropJob: Job? = null
    private fun startCropLoop() {
        cropJob?.cancel()
        cropJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val srcBitmap = binding.hiddenTextureView.bitmap
                if (srcBitmap == null || gridCols == 0 || gridRows == 0) {
                    delay(20)
                    continue
                }

                val tileW = srcBitmap.width / gridCols
                val tileH = srcBitmap.height / gridRows
                val left = myCol * tileW
                val top = myRow * tileH

                try {
                    val myTile = Bitmap.createBitmap(srcBitmap, left, top, tileW, tileH)
                    val rotated = rotateBitmap(myTile, myRotation)

                    withContext(Dispatchers.Main) {
                        binding.tileImageView.setImageBitmap(rotated)
                    }

                    if (rotated != myTile) {
                        myTile.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("CropError", "비트맵 크롭/회전 중 오류: ${e.message}")
                }
                delay(16)
            }
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, angle: Int): Bitmap {
        if (angle == 0) return bitmap
        val matrix = Matrix().apply { postRotate(angle.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cropJob?.cancel()
        player?.release()
        multicastLock?.takeIf { it.isHeld }?.release()
        socket?.disconnect()
    }
}