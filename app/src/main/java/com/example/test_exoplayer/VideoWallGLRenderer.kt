package com.example.test_exoplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VideoWallGLRenderer : GLSurfaceView.Renderer {

    private var program = 0
    private var textureID = 0
    private var surfaceTexture: SurfaceTexture? = null

    // 레이아웃 정보 (MainActivity에서 설정)
    var relX = 0f
    var relY = 0f
    var relW = 0f
    var relH = 0f
    var rotation = 0f // 45도 단위 각도

    private var vertexBuffer: FloatBuffer
    private var textureBuffer: FloatBuffer

    // ‼️ [추가] 셰이더 유니폼 핸들
    private var uRotationMatrixHandle = 0
    private var uRotationCenterHandle = 0

    // SurfaceTexture 준비 콜백
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    // 정점 좌표 (전체 화면을 덮는 사각형)
    private val vertexCoords = floatArrayOf(
        -1.0f, -1.0f,  // 왼쪽 아래
        1.0f, -1.0f,  // 오른쪽 아래
        -1.0f,  1.0f,  // 왼쪽 위
        1.0f,  1.0f   // 오른쪽 위
    )

    // 텍스처 좌표 (동적으로 계산됨)
    private var texCoords = FloatArray(8)

    init {
        // 정점 버퍼 초기화
        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexCoords)
        vertexBuffer.position(0)

        // 텍스처 좌표 버퍼 초기화
        textureBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // 외부 텍스처 생성 (비디오용)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureID = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        // ... (glTexParameteri WRAP_S, WRAP_T 생략) ...

        // SurfaceTexture 생성 (ExoPlayer가 여기에 비디오 출력)
        surfaceTexture = SurfaceTexture(textureID)
        onSurfaceTextureReady?.invoke(surfaceTexture!!)

        // 셰이더 프로그램 생성
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        // ‼️ [추가] 유니폼 핸들 가져오기
        uRotationMatrixHandle = GLES20.glGetUniformLocation(program, "uRotationMatrix")
        uRotationCenterHandle = GLES20.glGetUniformLocation(program, "uRotationCenter")

        Log.d("GLRenderer", "OpenGL Surface Created")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Log.d("GLRenderer", "Viewport: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        // SurfaceTexture에서 최신 비디오 프레임 업데이트
        surfaceTexture?.updateTexImage()

        // 화면 클리어
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 셰이더 프로그램 사용
        GLES20.glUseProgram(program)

        // 텍스처 좌표 업데이트 (크롭만 담당)
        updateTextureCoordinates()

        // ‼️ [추가] 회전 유니폼 변수 설정
        // 1. 회전 중심 계산 (크롭 영역의 중심)
        val centerX = relX + relW / 2.0f
        val centerY = relY + relH / 2.0f
        GLES20.glUniform2f(uRotationCenterHandle, centerX, centerY)

        // 2. 회전 행렬 계산
        // 서버가 보낸 각도(CW, 시계방향)를 GL 표준(CCW, 반시계)에 맞게 변환 (-1 곱함)
        val angleRad = Math.toRadians(-rotation.toDouble()).toFloat()
        val cos = Math.cos(angleRad.toDouble()).toFloat()
        val sin = Math.sin(angleRad.toDouble()).toFloat()

        // 2x2 회전 행렬 [cos, sin, -sin, cos] (Column-major order)
        val rotationMatrix = floatArrayOf(cos, sin, -sin, cos)

        GLES20.glUniformMatrix2fv(uRotationMatrixHandle, 1, false, rotationMatrix, 0)


        // 정점 속성 설정
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle, 2,
            GLES20.GL_FLOAT, false,
            0, vertexBuffer
        )

        // 텍스처 좌표 속성 설정
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(
            texCoordHandle, 2,
            GLES20.GL_FLOAT, false,
            0, textureBuffer
        )

        // 텍스처 유닛 설정
        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID)
        GLES20.glUniform1i(textureHandle, 0)

        // 그리기
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 정리
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun updateTextureCoordinates() {
        // ‼️ [수정] 90도 회전(when) 로직 완전 삭제

        if (relW <= 0f || relH <= 0f) {
            // 레이아웃 정보가 없으면 전체 화면 표시 (Y가 아래로 향하는 좌표계)
            texCoords = floatArrayOf(
                0f, 1f,  // 왼쪽 아래
                1f, 1f,  // 오른쪽 아래
                0f, 0f,  // 왼쪽 위
                1f, 0f   // 오른쪽 위
            )
        } else {
            // Y가 아래로 향하는 좌표계 (0,0 = top-left)
            val left = relX
            val right = relX + relW
            val top = relY
            val bottom = relY + relH

            // 0도 기준의 기본 크롭 좌표만 설정 (셰이더가 회전시킬 것임)
            texCoords = floatArrayOf(
                left, bottom,  // 왼쪽 아래
                right, bottom, // 오른쪽 아래
                left, top,     // 왼쪽 위
                right, top     // 오른쪽 위
            )
        }

        // 텍스처 버퍼 업데이트
        textureBuffer.clear()
        textureBuffer.put(texCoords)
        textureBuffer.position(0)
    }

    fun release() {
        surfaceTexture?.release()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        var program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        // ... (링크 상태 확인 코드 생략) ...

        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        // ... (컴파일 상태 확인 코드 생략) ...

        return shader
    }

    companion object {
        // ‼️ [수정] 45도 회전을 위한 버텍스 셰이더
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            
            // [추가] 회전 유니폼 변수
            uniform mat2 uRotationMatrix; // 2x2 회전 행렬
            uniform vec2 uRotationCenter; // 회전 중심 (텍스처 좌표계)

            void main() {
                gl_Position = aPosition;
                
                // [수정] 텍스처 좌표 회전
                // 1. 텍스처 좌표를 회전 중심으로 이동
                // 2. 회전 행렬 적용
                // 3. 다시 원래 위치로 이동
                vTexCoord = uRotationMatrix * (aTexCoord - uRotationCenter) + uRotationCenter;
            }
        """

        // 프래그먼트 셰이더는 동일
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}