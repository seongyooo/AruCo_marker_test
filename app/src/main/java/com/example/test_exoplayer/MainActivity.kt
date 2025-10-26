package com.example.test_exoplayer

import android.content.Context
import android.net.wifi.WifiManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.test_exoplayer.databinding.ActivityMainBinding
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var socket: Socket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var glRenderer: VideoWallGLRenderer

    private var myMarkerId = -1
//    private var myRotation = 0f
//    private var rel_x = 0f
//    private var rel_y = 0f
//    private var rel_w = 0f
//    private var rel_h = 0f

    private var myNormalizedCorners = FloatArray(8)

    private var isSurfaceReady = false
    private var isLayoutReady = false
    private var surface: Surface? = null

    // ‼️ 본인 환경에 맞게 서버 IP 수정
    private val serverUrl = "http://192.168.50.24:25000"
    private val streamUrl = "udp://224.1.1.1:1234"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 멀티캐스트 락 (UDP 수신에 필수)
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("multicastLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        // 시스템 UI 숨기기 (몰입 모드)
        hideSystemUI()

        // GLSurfaceView 설정
        setupGLSurfaceView()

        // Socket 연결
        setupSocket()
    }

    private fun setupGLSurfaceView() {
        glSurfaceView = GLSurfaceView(this)
        glSurfaceView.setEGLContextClientVersion(2)

        glRenderer = VideoWallGLRenderer()
        glRenderer.onSurfaceTextureReady = { surfaceTexture ->
            Log.d("VideoSetup", "GLSurfaceTexture 준비 완료.")
            this.surface = Surface(surfaceTexture)
            isSurfaceReady = true
            runOnUiThread {
                tryStartVideo()
            }
        }

        glSurfaceView.setRenderer(glRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        binding.glContainer.addView(glSurfaceView, layoutParams)
        binding.glContainer.visibility = View.INVISIBLE

        Log.d("VideoSetup", "GLSurfaceView 설정 완료")
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupSocket() {
        try {
            socket = IO.socket(serverUrl)
        } catch (e: Exception) {
            Log.e("Socket.IO", "소켓 URL 오류: $e")
            binding.tvStatus.text = "소켓 URL 오류:\n$serverUrl"
            return
        }


        socket?.on(Socket.EVENT_CONNECT) {
            Log.d("Socket.IO", "서버 연결!")
            socket?.emit("client_ready")
            runOnUiThread {
                binding.tvStatus.text = "서버 연결됨, 대기 중..."
            }
        }

        // ‼️ [개선] 연결 끊김 이벤트 처리
        socket?.on(Socket.EVENT_DISCONNECT) {
            Log.w("Socket.IO", "서버 연결 끊어짐.")
            runOnUiThread {
                binding.tvStatus.text = "서버 연결 끊어짐... 재연결 시도 중"
                binding.tvStatus.visibility = View.VISIBLE
                binding.markerImageView.visibility = View.GONE
                binding.glContainer.visibility = View.INVISIBLE

                // 상태 초기화
                player?.stop()
                player?.release()
                player = null
                isLayoutReady = false
                // isSurfaceReady는 true로 유지 (GL Surface는 살아있으므로)
            }
        }

        socket?.on("server_command") { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                val payload = args[0] as JSONObject
                val cmd = payload.optString("command")

                when (cmd) {
                    "assign_id" -> {
                        myMarkerId = payload.getInt("marker_id")
                        runOnUiThread {
                            binding.tvStatus.text = "내 마커ID: $myMarkerId"
                        }
                        Log.d("Socket.IO", "마커 ID 할당: $myMarkerId")
                    }
                    "show_marker" -> {
                        Log.d("Socket.IO", "마커 표시 요청 수신")
                        runOnUiThread {
                            showMarkerUI()
                        }

                        // ‼️ [필수] 서버에 마커 UI 준비 완료 신호 전송
                        socket?.emit("marker_ready")
                        Log.d("Socket.IO", "서버에 'marker_ready' 신호 전송")
                    }
                    "start_playback" -> {
                        Log.d("Socket.IO", "비디오 재생 시작 명령 수신")
                        val layoutJson = JSONObject(payload.getString("data"))
                        setGridLayoutInfo(layoutJson)
                        isLayoutReady = true
                        runOnUiThread {
                            tryStartVideo()
                        }
                    }
                }
            }
        }

        socket?.connect()
    }

    private fun showMarkerUI() {
        binding.glContainer.visibility = View.INVISIBLE
        binding.markerImageView.visibility = View.VISIBLE

        val resourceId = resources.getIdentifier("marker_$myMarkerId", "drawable", packageName)
        if (resourceId != 0) {
            binding.markerImageView.setImageResource(resourceId)
            binding.tvStatus.text = "마커 표시 중 (ID: #$myMarkerId)"
        } else {
            binding.tvStatus.text = "오류: 마커 이미지 없음 (ID: #$myMarkerId)"
        }
    }

    // ‼️ 이 함수를 통째로 교체하세요.
    private fun setGridLayoutInfo(json: JSONObject) {
        val layoutArray = json.getJSONArray("layout")

        for (i in 0 until layoutArray.length()) {
            val marker = layoutArray.getJSONObject(i)
            if (marker.getInt("id") == myMarkerId) {

                // ‼️ [핵심] "relative_corners" 키가 있는지 확인
                if (!marker.has("relative_corners")) {
                    Log.e("LayoutInfo", "오류: 서버가 'relative_corners'를 보내지 않았습니다. (이전 버전 서버?)")
                    return
                }

                val cornersArray = marker.getJSONArray("relative_corners")

                // ArUco 코너 순서: [0: TL, 1: TR, 2: BR, 3: BL]
                val tl = cornersArray.getJSONObject(0)
                val tr = cornersArray.getJSONObject(1)
                val br = cornersArray.getJSONObject(2)
                val bl = cornersArray.getJSONObject(3)

                // ‼️ GL 텍스처 좌표 순서(BL, BR, TL, TR)에 맞게 8-float 배열 생성
                // (서버가 Y-Flipped 값을 보냈으므로 1.0f - ... 로직 제거)
                val myNormalizedCorners = floatArrayOf(
                    bl.getDouble("x").toFloat(), bl.getDouble("y").toFloat(), // 0: BL
                    br.getDouble("x").toFloat(), br.getDouble("y").toFloat(), // 1: BR
                    tl.getDouble("x").toFloat(), tl.getDouble("y").toFloat(), // 2: TL
                    tr.getDouble("x").toFloat(), tr.getDouble("y").toFloat()  // 3: TR
                )

                // GLRenderer에 레이아웃 정보 업데이트
                runOnUiThread {
                    glRenderer.updateTextureCoordinates(myNormalizedCorners)
                }

                Log.d("LayoutInfo", "4-Corner Coords [BL, BR, TL, TR] 업데이트 완료")
                break
            }
        }
    }

    private fun tryStartVideo() {
        Log.d("VideoSetup", "tryStartVideo() 호출 - isSurfaceReady=$isSurfaceReady, isLayoutReady=$isLayoutReady, player=$player")

        // ‼️ 모든 조건이 충족되었는지 확인
        if (isSurfaceReady && isLayoutReady && player == null) {
            Log.d("VideoSetup", "✅ 모든 조건 충족! 비디오 재생을 시작합니다.")

            // UI 변경: 마커 숨기고, GL(비디오) 화면 표시
            binding.markerImageView.visibility = View.GONE
            binding.tvStatus.visibility = View.GONE
            binding.glContainer.visibility = View.VISIBLE

            // 플레이어 실행
            startPlayerWithSurface(surface!!)
        } else {
            Log.w("VideoSetup", "⚠️ 조건 미충족 - Surface준비:$isSurfaceReady, 레이아웃준비:$isLayoutReady, Player존재:${player != null}")
        }
    }
    @OptIn(UnstableApi::class)
    private fun startPlayerWithSurface(surface: Surface) {
        Log.d("VideoSetup", "ExoPlayer 생성 시작")

        // 1. loadControl 정의 (이 부분은 올바르게 작성하셨습니다)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                3000,  // MinBufferMs
                30000, // MaxBufferMs
                1500,  // BufferForPlaybackMs
                1500   // BufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // ‼️ [수정] player = ... 블록을 하나로 합쳤습니다.
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl) // ‼️ loadControl을 여기에 적용합니다.
            .build()
            .apply { // ‼️ .apply 블록 시작

                // --- .apply 블록 안에 모든 설정을 넣습니다 ---

                setVideoSurface(surface)
                setMediaItem(MediaItem.fromUri(streamUrl))
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true

                // ‼️ 기존에 있던 에러 리스너도 여기에 포함합니다.
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("ExoPlayer", "플레이어 오류 발생: ${error.message}", error)
                        runOnUiThread {
                            binding.tvStatus.text = "오류: 스트림 재생 실패\n${error.errorCodeName}"
                            binding.tvStatus.visibility = View.VISIBLE
                            binding.glContainer.visibility = View.INVISIBLE
                        }
                    }
                })

                // ‼️ prepare()도 .apply 블록 안에서 호출합니다.
                prepare()

            } // ‼️ .apply 블록 닫기

        // ‼️ [삭제] 중복되었던 두 번째 player = ... 블록은 여기서 삭제되었습니다.

        Log.d("VideoSetup", "ExoPlayer 시작됨 (버퍼 튜닝 적용)")
    }

    override fun onResume() {
        super.onResume()
        if (::glSurfaceView.isInitialized) {
            glSurfaceView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::glSurfaceView.isInitialized) {
            glSurfaceView.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        if (::glRenderer.isInitialized) {
            glRenderer.release()
        }
        multicastLock?.takeIf { it.isHeld }?.release()
        socket?.disconnect()
    }
}