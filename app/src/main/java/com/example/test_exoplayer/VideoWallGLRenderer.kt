package com.example.test_exoplayer // ‼️ 본인의 패키지 이름으로 수정하세요

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

    // ‼️ [삭제] 회전 및 사각형 관련 변수 모두 삭제
    // var relX, relY, relW, relH, rotation 등...

    private var vertexBuffer: FloatBuffer
    private var textureBuffer: FloatBuffer

    // SurfaceTexture 준비 콜백
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    // 정점 좌표 (전체 화면을 덮는 사각형 - 고정)
    private val vertexCoords = floatArrayOf(
        -1.0f, -1.0f,  // 왼쪽 아래 (BL)
        1.0f, -1.0f,  // 오른쪽 아래 (BR)
        -1.0f,  1.0f,  // 왼쪽 위 (TL)
        1.0f,  1.0f   // 오른쪽 위 (TR)
    )

    // 텍스처 좌표 (동적으로 계산됨 - 8개 float)
    private var texCoords = FloatArray(8)

    init {
        // 정점 버퍼 초기화
        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexCoords)
        vertexBuffer.position(0)

        // 텍스처 좌표 버퍼 초기화 (8개 float 공간)
        textureBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // ‼️ [수정] 기본값으로 0,0,0,0... 대신 전체 텍스처를 채움
        // (MainActivity에서 업데이트하기 전까지 검은 화면 대신 원본 영상이 보일 수 있음)
        updateTextureCoordinates(floatArrayOf(
            0f, 1f, // BL
            1f, 1f, // BR
            0f, 0f, // TL
            1f, 0f  // TR
        ))
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
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        // SurfaceTexture 생성 (ExoPlayer가 여기에 비디오 출력)
        surfaceTexture = SurfaceTexture(textureID)
        onSurfaceTextureReady?.invoke(surfaceTexture!!)

        // 셰이더 프로그램 생성
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        // ‼️ [삭제] 회전 관련 유니폼 핸들 가져오기 (uRotationMatrixHandle 등) 삭제

        Log.d("GLRenderer", "OpenGL Surface Created (4-Corner Mapping)")
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

        // ‼️ [삭제] updateTextureCoordinates() 호출 삭제 (MainActivity가 외부에서 호출)

        // ‼️ [삭제] 회전 유니폼 변수 설정 로직 (centerX, centerY, angleRad, Matrix 등) 모두 삭제

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
            0, textureBuffer // ‼️ 이 버퍼는 updateTextureCoordinates()에 의해 업데이트됨
        )

        // 텍스처 유닛 설정
        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID)
        GLES20.glUniform1i(textureHandle, 0)

        // 그리기 (4개의 정점 = 1개의 사각형 스트립)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 정리
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /**
     * ‼️ [수정] MainActivity로부터 8-float 배열(BL, BR, TL, TR 순서)을 받아
     * 텍스처 좌표 버퍼를 업데이트합니다.
     */
    fun updateTextureCoordinates(newCoords: FloatArray) {
        if (newCoords.size != 8) {
            Log.e("GLRenderer", "잘못된 텍스처 좌표 배열 수신. 크기: ${newCoords.size}")
            return
        }

        texCoords = newCoords
        try {
            textureBuffer.clear()
            textureBuffer.put(texCoords)
            textureBuffer.position(0)
        } catch (e: Exception) {
            Log.e("GLRenderer", "텍스처 버퍼 업데이트 중 오류", e)
        }
    }

    fun release() {
        surfaceTexture?.release()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES20.glCreateProgram()
        if (program == 0) {
            Log.e("GLRenderer", "glCreateProgram 실패")
            return 0
        }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e("GLRenderer", "프로그램 링크 실패: ")
            Log.e("GLRenderer", GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            Log.e("GLRenderer", "glCreateShader 실패 (타입: $type)")
            return 0
        }
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("GLRenderer", "셰이더 컴파일 실패 (타입: $type):")
            Log.e("GLRenderer", GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        // ‼️ [수정] 모든 회전 로직이 제거된 가장 단순한 버텍스 셰이더
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord; 
            }
        """

        // 프래그먼트 셰이더는 수정 없음 (동일)
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