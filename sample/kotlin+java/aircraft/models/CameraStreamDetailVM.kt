package dji.sampleV5.aircraft.models

import android.view.Surface
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraType
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.flightassistant.VisionAssistDirection
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.et.set
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.manager.interfaces.ICameraStreamManager.FrameFormat
import dji.v5.manager.interfaces.ICameraStreamManager.ScaleType
import dji.v5.utils.common.DJIExecutor
import dji.v5.utils.common.DateUtils
import dji.v5.utils.common.LogPath
import dji.v5.utils.common.LogUtils
import dji.v5.utils.common.StringUtils
import dji.v5.ux.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

// Image formatting dependencies
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64                                                                          // Import Android's Base64 utility
import com.google.gson.Gson                                                                         // Already imported, just for clarity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer                                                                          // Add this import

const val TAG = "CameraStreamDetailFragmentVM"

class CameraStreamDetailVM : DJIViewModel() {

    private val _availableLensListData = MutableLiveData<List<CameraVideoStreamSourceType>>(ArrayList())
    private val _currentLensData = MutableLiveData(CameraVideoStreamSourceType.DEFAULT_CAMERA)
    private val _cameraName = MutableLiveData("Unknown")
    private val _isVisionAssistEnabled = MutableLiveData(false)
    private val _visionAssistViewDirection = MutableLiveData(VisionAssistDirection.UNKNOWN)
    private val _visionAssistViewDirectionRange = MutableLiveData<List<VisionAssistDirection>>(ArrayList())

    private var cameraIndex = ComponentIndexType.UNKNOWN
    private var cameraType = ""
    private var isMotorOn = false

    // ThingsBoard config --------------------------------------------------------------------------
    private val THINGSBOARD_HOST = "srv-iot.diatel.upm.es"                                          // ThingsBoard server (UPM Diatel)
    private val DEVICE_ACCESS_TOKEN = "1geqf5n3orfgdqm0l7f3"                                        // Device access token
    private val TELEMETRY_ENDPOINT = "api/v1/$DEVICE_ACCESS_TOKEN/telemetry"                        // Telemetry end-point for ThingsBoard device
    private val THINGSBOARD_IMAGE_TAG = "ThingsBoardImageSender"                                    // Tag for Logcat messages related to image sending
    private val THINGSBOARD_TAG = "ThingsBoard"                                                     // Added for clarity

    // MQTT related object constructors ------------------------------------------------------------
    private val httpClient = OkHttpClient()
    private val gson = Gson()                                                                       // Object for converting Kotlin objects to JSON
    private var treeIdCounter: Int = 0                                                              // Variable to hold the treeId, initialized to 0

    // Image config --------------------------------------------------------------------------------
    private val TARGET_WIDTH = 1280                                                                 // Image width in pixels
    private val TARGET_HEIGHT = 720                                                                 // Image height in pixels
    private val JPEG_QUALITY = 75                                                                   // Compression rate. 0-100, 75 is a good balance for quality/size

    // Tree ID counter -----------------------------------------------------------------------------
    private val _currentTreeId = MutableLiveData<Int>()                                             // Declare the Tree ID as a mutable live data so that the variable can communicate (it is observable) with its equal from ThesisDetailFragment
    val currentTreeId: LiveData<Int> get() = _currentTreeId                                         // Now this one is not mutable and it is just meant to be watched from the ThesisDetailFragment. The "get()" means that every time the variable is called, it returns (read-only) the value "_currentTreeId"

    // hasFinishedState is managed by the ViewModel. Typical setup for MutableLiveData
    private val _hasFinishedState = MutableLiveData(0)                                        // Initialize to 0 (false)
    val hasFinishedState: LiveData<Int> = _hasFinishedState                                         // Expose as LiveData

    private val visionAssistStatusListener = object :
        ICameraStreamManager.VisionAssistStatusListener {
        override fun onVisionAssistEnabled(isEnable: Boolean) {
            _isVisionAssistEnabled.postValue(isEnable)
        }

        override fun onVisionAssistViewDirectionUpdated(mode: VisionAssistDirection) {
            _visionAssistViewDirection.postValue(mode)
        }

        override fun onVisionAssistViewDirectionRangeUpdated(modes: MutableList<VisionAssistDirection>) {
            _visionAssistViewDirectionRange.postValue(modes)
        }
    }

    private var streamFile: File? = null
    private var streamFileOutputStream: FileOutputStream? = null

    private val streamListener = ICameraStreamManager.ReceiveStreamListener { data, offset, length, info ->
        if (streamFile == null) {
            val fileName = "[${cameraIndex.name}]${DateUtils.getSystemTime()}.${info.mimeType.name.lowercase(Locale.ROOT)}"
            ToastUtils.showToast("begin to save,$fileName")
            streamFile = File(LogUtils.getLogPath(), fileName)
            streamFileOutputStream = FileOutputStream(streamFile)
            return@ReceiveStreamListener
        }
        DJIExecutor.getExecutor().execute {
            try {
                streamFileOutputStream?.write(data, offset, length)
            } catch (e: Exception) {
                //do nothing
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        setCameraIndex(ComponentIndexType.UNKNOWN)
        MediaDataCenter.getInstance().cameraStreamManager.removeVisionAssistStatusListener(visionAssistStatusListener)
        KeyManager.getInstance().cancelListen(this)
        doStopDownloadStreamToLocal()
    }

    /**
     * MAIN CUSTOM FUNCTION TO CAPTURE IMAGES AND SEND THEM VIA MQTT
     * The workflow of this function consists of taking a capture from the video stream of the drone
     * in the provided YUV420_888 format by the MSDK. Then, this image is converted to NV21
     */
    fun captureAndSendImageToThingsBoard() {
        if (cameraIndex == ComponentIndexType.UNKNOWN) {                                            // If 'cameraIndex' is set to 'UNKNOWN', it means that the onboard drone camera system has not been properly initialized
            ToastUtils.showToast("Camera not initialized.")
            return                                                                                  // Exits the function to avoid errors
        }

        ToastUtils.showToast("Capturing image...")
        LogUtils.d(THINGSBOARD_IMAGE_TAG, "Attempting to capture frame for sending.")

        // Request a single frame in YUV420_888 format -> Y = Iluminance, UV = Chrominance
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(                         // Get an instance of the camera stream manager, which is the object that manages camera operations. 'addFrameListener' registers a listener to receive camera frames
            cameraIndex,                                                                            // 'cameraIndex' specifies the onboard camera to use, which is reduced to the only one the Mini 3 does have
            ICameraStreamManager.FrameFormat.YUV420_888,                                            // Request the image frame in YUV format
            object : ICameraStreamManager.CameraFrameListener {                                     // Define an anonymous inner class that implements 'CameraFrameListener' interface. The object will be called when a new frame is available
                override fun onFrame(frameData: ByteArray, offset: Int, length: Int, width: Int, height: Int, format: FrameFormat) {  // Callback method that gets executed when a camera frame is successfully captured. 'framedata: ByteArray' is the raw byte array containing the image data. 'offset: Int' is the offset within 'frameData' where the image data begins. 'length: Int' is the length of the image within 'frameData'. 'width: Int' is the frame width in pixels. 'height: Int' is the frame height in pixels. 'format: FrameFormat' os the format of the frame, YUV420_888
                    MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(this)  // Remove the listener immediately to avoid getting more than a single frame

                    LogUtils.d(THINGSBOARD_IMAGE_TAG, "Received frame: ${width}x${height}, format: ${format.name}")

                    CoroutineScope(Dispatchers.IO).launch {                                         // Launch a Kotlin background thread to avoid blocking the application UI
                        try {                                                                       // Use 'try' to handle possible exceptions
                            val nv21Bytes = convertYUV420_888toNV21(frameData, width, height)       // Convert the raw YUV420_888 image to NV21 (encoding for YCrCb format, very common in Android for image processing)
                            if (nv21Bytes == null) {                                                // If no bytes have been converted to NV21, notify the error
                                withContext(Dispatchers.Main) {                                     // 'withContext(Dispatchers.Main)' is used to send the error from the background thread to the main thread
                                    ToastUtils.showToast("Failed to convert YUV to NV21.")
                                }
                                LogUtils.e(THINGSBOARD_IMAGE_TAG, "YUV to NV21 conversion failed.")
                                return@launch                                                       // Exit the background thread
                            }

                            // Create YuvImage from NV21 bytes
                            val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, width, height, null)  // Create a 'YuvImage' object from the NV21 byte array. This object is very suitable for Android image processing utilities. 'ImageFormat.NV21' specifies format and 'width' and 'height', the dimensions. 'null' argument is for strides, which are automatically calculated
                            val out = ByteArrayOutputStream()                                       // Initialize 'ByteArrayOutputStream', which is used to write the compressed JPEG image data into memory

                            // Calculate the target rectangle for compression, maintaining aspect ratio. The Rect here is for the source region, the compression will scale it
                            val resizeRect = calculateResizeRect(width, height, TARGET_WIDTH, TARGET_HEIGHT)  // Determine the rectangular region of the 'YuvImage' to use for compression. It is done to ensure the image is resized to 'TARGET_WIDTH' and 'TARGET_HEIGHT' while maintaining the aspect ratio. 'Rect' defines the portion of the original image to be compressed

                            // Convert Y'UV image to compressed JPEG that automatically fits the given rectangle
                            yuvImage.compressToJpeg(resizeRect, JPEG_QUALITY, out)                  // 'resizeRect' is the rectangle defining the portion of the YUV image to compress. 'JPEG_QUALITY' is a macro that defines the JPEG quality compression (int from 0 to 100)
                            val jpegByteArray = out.toByteArray()                                   // Convert the contents of 'ByteArrayOutputStream' into a ByteArray containing the JPEG image data
                            out.close()                                                             // Close the output stream, releasing its associated resources

                            // Base64 encode the JPEG byte array
                            val base64Image = Base64.encodeToString(jpegByteArray, Base64.NO_WRAP)  // Encode 'jpegByteArray' into a Base64 string. Base64 encoding is the key to send the image inside JSON format. 'Base64.NO_WRAP' prevents the output string from being wrapped with newline characters
                            LogUtils.d(THINGSBOARD_IMAGE_TAG, "JPEG image converted and Base64 encoded. Size: ${base64Image.length / 1024} KB")  // By dividing the length of the image by 1024, it is transformed from bytes to kilobytes

                            // Decode the jpegByteArray back to Bitmap object
                            val bitmap: Bitmap? = BitmapFactory.decodeByteArray(jpegByteArray, 0, jpegByteArray.size)  // This step is crucial for the RBG decomposition and analysis

                            if (bitmap != null) {                                                   // If the Bitmap was successfully created, get the dimensions
                                val width = bitmap.width
                                val height = bitmap.height
                                val pixels = IntArray(width * height)
                                bitmap.getPixels(pixels, 0, width, 0, 0, width, height) // '.getPixels' gets the ARGB information of the image pixels

                                val numPixels = width * height                                      // Number of pixels of the image

                                // For Median: Store all values to sort later
                                val redValues = mutableListOf<Int>()
                                val greenValues = mutableListOf<Int>()
                                val blueValues = mutableListOf<Int>()

                                // Process pixels to get RGB values
                                for (pixel in pixels) {                                             // For all the pixels of the image, get the amount of RGB from each
                                    val r = Color.red(pixel)
                                    val g = Color.green(pixel)
                                    val b = Color.blue(pixel)

                                    redValues.add(r)                                                // Add the values for each colour to each mutable list
                                    greenValues.add(g)
                                    blueValues.add(b)
                                }

                                // Calculate Median
                                redValues.sort()                                                    // Sorting can be computationally expensive for very large images, so expect a bit of delay in the application
                                greenValues.sort()
                                blueValues.sort()

                                val medianR = if (numPixels > 0) getMedian(redValues) else 0.0      // Get median colour for each channel only if at least pixel of each colour has been found. Assign 0.0 as default if non has been found
                                val medianG = if (numPixels > 0) getMedian(greenValues) else 0.0
                                val medianB = if (numPixels > 0) getMedian(blueValues) else 0.0

                                val medianR_rounded = "%.2f".format(Locale.US, medianR).toDouble()  // Format the values to 2 decimal figures and convert them to double for better precision
                                val medianG_rounded = "%.2f".format(Locale.US, medianG).toDouble()
                                val medianB_rounded = "%.2f".format(Locale.US, medianB).toDouble()

                                val sentTreeId = treeIdCounter                                      // Store current treeId before incrementing for upcoming purposes
                                treeIdCounter++                                                     // Increment it after

                                // Get the current hasFinishedState value from LiveData (DO NOT TOGGLE HERE)
                                val currentHasFinishedValue = _hasFinishedState.value ?: 0

                                // Create a mutable map to form the JSON payload for ThingsBoard
                                val payload = mutableMapOf<String, Any>(
                                    "cameraImage" to base64Image,                                   // Base64 image, most convinient format for JSON format
                                    "treeId" to sentTreeId,
                                    "medianRed" to medianR_rounded,
                                    "medianGreen" to medianG_rounded,
                                    "medianBlue" to medianB_rounded,
                                    "hasFinished" to currentHasFinishedValue                        // Last 'hasFinished' status received
                                )

                                // Convert the payload map to a JSON string using Gson
                                val jsonPayload = gson.toJson(payload)

                                // Recycle the bitmap to free up memory as the frame has been correctly stored in the JSON variable
                                bitmap.recycle()

                                LogUtils.d(THINGSBOARD_IMAGE_TAG, "Calculated Median RGB: R=%.2f, G=%.2f, B=%.2f".format(Locale.US, medianR_rounded, medianG_rounded, medianB_rounded))
                                LogUtils.d(THINGSBOARD_IMAGE_TAG, "Publishing image payload (first 100 chars): ${jsonPayload.substring(0, minOf(jsonPayload.length, 100))}...")

                                // Send via HTTP POST to ThingsBoard
                                val requestBody = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())  // Create a request body from the JSON payload with "application/json" media type
                                val request = Request.Builder()                                     // Build the HTTP POST request
                                    .url("https://$THINGSBOARD_HOST/$TELEMETRY_ENDPOINT")           // Set the HTTP API endpoint URL
                                    .post(requestBody)                                              // Set the request method to POST and attach the request body
                                    .build()

                                val response = httpClient.newCall(request).execute()                // Execute the HTTP request synchronously

                                withContext(Dispatchers.Main) {                                     // Switch back to the main UI thread to update the UI
                                    if (response.isSuccessful) {                                    // If the HTTP method was executed successfully, send the corresponding notifications
                                        val successMessage =
                                            "Successfully sent image to ThingsBoard. Size: ${jpegByteArray.size / 1024} KB. treeId: ${treeIdCounter - 1}, hasFinished: $currentHasFinishedValue"
                                        LogUtils.i(THINGSBOARD_IMAGE_TAG, successMessage)
                                        ToastUtils.showToast(successMessage)
                                        // Post the updated treeId to LiveData on successful send
                                        _currentTreeId.postValue(sentTreeId)                        // Posted on ThesisDetailFragment
                                    } else {                                                        // In case it retrieved any error, notify it, too
                                        val errorMessage =
                                            "Failed to send image: ${response.code} - ${response.message} (Body: ${response.body?.string()})"
                                        LogUtils.e(THINGSBOARD_IMAGE_TAG, errorMessage)
                                        ToastUtils.showToast(errorMessage)
                                    }
                                }
                            } else {                                                                // In case the Bitmap is empty
                                LogUtils.e(                                                         // Notify it in the log
                                    THINGSBOARD_IMAGE_TAG,
                                    "Failed to decode image from byte array."
                                )
                                withContext(Dispatchers.Main) {                                     // Send the information back to the main thread to pop a toast message
                                    ToastUtils.showToast("Failed to process image for color analysis.")
                                }
                            }
                        // EXCEPTIONS
                        } catch (e: Exception) {                                                    // Exception for the image conversion
                            withContext(Dispatchers.Main) {
                                val errorMessage = "Error processing or sending image: ${e.message}"
                                LogUtils.e(THINGSBOARD_IMAGE_TAG, errorMessage, e)
                                ToastUtils.showToast(errorMessage)
                            }
                        }
                    }
                }
            })
    }

    /**
     * Toggles the hasFinished state (0 to 1, or 1 to 0).
     * This function only updates the internal state and the LiveData; it does not send data.
     */
    fun toggleHasFinishedState() {
        val current = _hasFinishedState.value ?: 0
        val newState = 1 - current
        _hasFinishedState.postValue(newState)
        LogUtils.i(THINGSBOARD_TAG, "hasFinished state toggled to: $newState")
        ToastUtils.showToast("hasFinished state set to: $newState")
    }

    /**
     * Converts YUV420_888 byte array (from DJI SDK) to NV21 format.
     * This implementation assumes a common YUV420 planar layout where Y, U, and V are stored
     * contiguously, but with U and V potentially interleaved and subsampled.
     * This is a heuristic; exact behavior depends on DJI's specific YUV420_888 output.
     *
     * @param yuv420Bytes The byte array from DJI SDK's FrameFormat.YUV420_888.
     * @param width Width of the frame.
     * @param height Height of the frame.
     * @return NV21 byte array, or null if conversion fails.
     */
    private fun convertYUV420_888toNV21(yuv420Bytes: ByteArray, width: Int, height: Int): ByteArray? {
        if (yuv420Bytes.isEmpty() || width <= 0 || height <= 0) {                                   // Checks if the input YUV byte array is empty or if the image dimensions are 0
            return null                                                                             // And returns null to quickly exit the function to avoid resources consumption and errors
        }

        // NV21 format: Y plane, then interleaved VU plane
        val nv21Size = width * height + width * height / 2                                          // Size = (width * height) for Y + (width * height / 2) for UV
        val nv21 = ByteArray(nv21Size)                                                              // Create a byte array to store te NV21 image information with the corresponding size

        // Copy Y plane. Assume the Y plane is at the beginning of the frameData and its size is width * height
        val ySize = width * height                                                                  // Remember, Y plane is luminance
        System.arraycopy(yuv420Bytes, 0, nv21, 0, ySize)                                            // 'yuv420bytes' is the source array, '0' is the offset, so from the very beginning of the array, 'nv21' is the destination array, '0' is the offset for the destination array, again, from the very beginning and 'ySize' is the amount of bytes to copy

        // Copy UV plane (interleaved 'V' then 'U' for NV21)
        // This is the tricky part. DJI's YUV420_888 might have 'U' then 'V', or 'V' then 'U', or separate.
        // It is assumed the most common case for YUV420 planar, where 'U' and 'V' follow 'Y',
        // and try to interleave them for NV21.
        // A common YUV420_888 arrangement puts 'U' and 'V' planes after 'Y'.
        // 'U' plane starts at ySize, 'V' plane starts at "ySize + (ySize / 4)"
        // Each UV plane is "(width/2) * (height/2)"
        val uvSize = width * height / 4                                                             // Size of one chrominance plane (U or V)
        val uOffset = ySize                                                                         // Define the beginning of the 'U' plane assuming its first byte index is right after the 'Y' one
        val vOffset = ySize + uvSize                                                                // Define the beginning of the 'V' plane assuming its first byte index is right after the 'U' one

        // Check if yuv420Bytes has enough data for U and V planes
        if (yuv420Bytes.size < vOffset + uvSize) {                                                  // The YUV byte array must be coherent according to the established dimensions
            LogUtils.e(THINGSBOARD_IMAGE_TAG, "YUV420_888 byte array is too small for expected U and V planes.")
            return null                                                                             // Again, exit the function returning null
        }

        // Start writing UV data in NV21 after the Y plane
        var nv21_uv_idx = ySize                                                                     // Initialize a variable for the UV index to where the bytes should be written in the 'nv21' output byte array. 'Y' plane has already been copied, so it starts right after
        for (i in 0 until uvSize) {                                                           // Iterate the whole 'UV' plane to process all the chrominance information
            // NV21 is V then U interleaved. So, copy V first, then U
            nv21[nv21_uv_idx++] = yuv420Bytes[vOffset + i]                                          // 'V' first. "nv21[nv21_uv_idx]" writes a byte to the current position in the nv21 array, "yuv420Bytes[vOffset + i]" reads a byte from the 'V' plane of the input 'yuv420Bytes' at the current i offset, "nv21_uv_idx++" post-increments 'nv21_uv_idx'. This effectively moves the pointer forward by one byte in the 'nv21' array. This copies the 'V' component
            nv21[nv21_uv_idx++] = yuv420Bytes[uOffset + i]                                          // 'U' after 'V'. Same working method as for 'V' plane
        }

        return nv21
    }

    /**
     * Calculates the Rect for resizing while maintaining aspect ratio.
     * This will ensure the image is scaled down to fit within TARGET_WIDTH x TARGET_HEIGHT.
     * The Rect itself is for the source region (full original image).
     * The actual scaling happens within YuvImage.compressToJpeg.
     */
    private fun calculateResizeRect(originalWidth: Int, originalHeight: Int, targetWidth: Int, targetHeight: Int): Rect {
        // YuvImage.compressToJpeg uses the Rect to define the region of the YUV image to compress.
        // To resize the *entire* image, the Rect should cover the entire source image.
        // The scaling logic is handled internally by compressToJpeg based on the target dimensions
        // implied by the output stream and compression process.
        // We ensure aspect ratio by setting appropriate TARGET_WIDTH/HEIGHT.
        // So, the Rect passed here should always be the full source image bounds.
        return Rect(0, 0, originalWidth, originalHeight)
    }

    /**
     * Calculates the median for a list of values in double format
     */
    private fun getMedian(list: List<Int>): Double {
        if (list.isEmpty()) return 0.0                                                              // Return 0.0 by default if the list is empty
        val size = list.size                                                                        // Compute the list size to know if it is even or odd
        return if (size % 2 == 1) {                                                                 // Odd number of elements
            list[size / 2].toDouble()
        } else {                                                                                    // Even number of elements
            (list[size / 2 - 1] + list[size / 2]).toDouble() / 2.0
        }
    }

    fun setCameraIndex(cameraIndex: ComponentIndexType) {
        KeyManager.getInstance().cancelListen(this)
        if (this.cameraIndex == cameraIndex) {
            return
        }
        this.cameraIndex = cameraIndex
        if (this.cameraIndex == ComponentIndexType.UNKNOWN) {
            return
        }
        listenCameraName()
        listenAvailableLens()
        listenCurrentLens()
        listenVisionAssistStatus()
    }

    private fun listenCameraName() {
        CameraKey.KeyCameraType.create(cameraIndex).listen(this) {
            cameraType = it?.name ?: CameraType.NOT_SUPPORTED.name
            updateCameraName()
        }

        FlightControllerKey.KeyAreMotorsOn.create().listen(this) {
            isMotorOn = it == true
            updateCameraName()
        }
    }

    private fun updateCameraName() {
        _cameraName.postValue("")
        if (cameraIndex == ComponentIndexType.UNKNOWN) {
            return
        }
        if (cameraIndex == ComponentIndexType.FPV) {
            _cameraName.postValue(ComponentIndexType.FPV.name)
            return
        }
        if (cameraIndex == ComponentIndexType.VISION_ASSIST) {
            var msg = ComponentIndexType.VISION_ASSIST.name
            if (!isMotorOn) {
                msg = "$msg(${StringUtils.getResStr(R.string.uxsdk_assistant_video_empty_text)})"
            }
            _cameraName.postValue(msg)
            return
        }
        _cameraName.postValue(cameraType)
    }

    private fun listenAvailableLens() {
        _availableLensListData.postValue(arrayListOf())
        if (cameraIndex == ComponentIndexType.UNKNOWN) {
            return
        }
        CameraKey.KeyCameraVideoStreamSourceRange.create(cameraIndex).listen(this) {
            val list: List<CameraVideoStreamSourceType> = it ?: arrayListOf()
            _availableLensListData.postValue(list)
        }
    }

    private fun listenCurrentLens() {
        _currentLensData.postValue(CameraVideoStreamSourceType.DEFAULT_CAMERA)
        if (cameraIndex == ComponentIndexType.UNKNOWN) {
            return
        }
        CameraKey.KeyCameraVideoStreamSource.create(cameraIndex).listen(this) {
            if (it != null) {
                _currentLensData.postValue(it)
            } else {
                _currentLensData.postValue(CameraVideoStreamSourceType.DEFAULT_CAMERA)
            }
        }
    }

    private fun listenVisionAssistStatus() {
        MediaDataCenter.getInstance().cameraStreamManager.addVisionAssistStatusListener(visionAssistStatusListener)
    }

    fun changeCameraLens(lensType: CameraVideoStreamSourceType) {
        CameraKey.KeyCameraVideoStreamSource.create(cameraIndex).set(lensType)
    }

    fun putCameraStreamSurface(
        surface: Surface, width: Int, height: Int, scaleType: ScaleType
    ) {
        MediaDataCenter.getInstance().cameraStreamManager.putCameraStreamSurface(cameraIndex, surface, width, height, scaleType)
    }

    fun removeCameraStreamSurface(surface: Surface) {
        MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(surface)
    }

    fun downloadYUVImageToLocal(format: FrameFormat, formatName: String) {
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
            cameraIndex,
            format,
            object : ICameraStreamManager.CameraFrameListener {
                override fun onFrame(frameData: ByteArray, offset: Int, length: Int, width: Int, height: Int, format: FrameFormat) {
                    try {
                        val dirs = File(LogUtils.getLogPath() + "STREAM_PIC")
                        if (!dirs.exists()) {
                            dirs.mkdirs()
                        }
                        val fileName = "[${cameraIndex.name}][$width x $height]${DateUtils.getSystemTime()}.${formatName}"
                        val file = File(dirs.absolutePath, fileName)
                        FileOutputStream(file).use { stream ->
                            stream.write(frameData, offset, length)
                            stream.flush()
                            stream.close()
                            ToastUtils.showToast("Save to : ${file.path}")
                        }
                        LogUtils.i(TAG, "Save to : ${file.path}")
                    } catch (e: Exception) {
                        ToastUtils.showToast("Save fail : $e")
                    }
                    // Because only one frame needs to be saved, you need to call removeOnFrameListener here
                    // If you need to read frame data for a long time, you can choose to actually call remove OnFrameListener according to your needs
                    MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(this)
                }
            })
    }

    fun enableVisionAssist(enable: Boolean) {
        MediaDataCenter.getInstance().cameraStreamManager.enableVisionAssist(enable, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                ToastUtils.showToast("enableVisionAssist onSuccess $enable")
            }

            override fun onFailure(error: IDJIError) {
                ToastUtils.showToast("enableVisionAssist onFailure $enable,error:$error")
            }
        })
    }

    fun setVisionAssistViewDirection(direction: VisionAssistDirection) {
        MediaDataCenter.getInstance().cameraStreamManager.setVisionAssistViewDirection(direction, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                ToastUtils.showToast("set Direction onSuccess $direction")
            }

            override fun onFailure(error: IDJIError) {
                ToastUtils.showToast("set Direction onFailure $direction,error:$error")
            }
        })
    }

    fun beginDownloadStreamToLocal() {
        if (streamFile != null) {
            ToastUtils.showToast("Pls stop first.")
            return
        }
        MediaDataCenter.getInstance().cameraStreamManager.addReceiveStreamListener(cameraIndex, streamListener)
    }

    fun stopDownloadStreamToLocal() {
        if (streamFile == null) {
            ToastUtils.showToast("Pls begin first.")
            return
        }
        ToastUtils.showToast("stop to save,${streamFile?.name}")
        doStopDownloadStreamToLocal()
    }

    fun setStreamEncoderBitrate(bitrate: Int) {
        MediaDataCenter.getInstance().cameraStreamManager.setStreamEncoderBitrate(cameraIndex, bitrate)
    }

    fun getStreamEncoderBitrate() = MediaDataCenter.getInstance().cameraStreamManager.getStreamEncoderBitrate(cameraIndex)

    fun changeCameraMode(mode: CameraMode) {
        CameraKey.KeyCameraMode.create().set(mode)
    }

    private fun doStopDownloadStreamToLocal() {
        MediaDataCenter.getInstance().cameraStreamManager.removeReceiveStreamListener(streamListener)
        try {
            streamFileOutputStream?.flush()
            streamFileOutputStream?.close()
            streamFileOutputStream = null
            streamFile = null
        } catch (e: Exception) {
            //do nothing
        }
    }

    val availableLensListData: LiveData<List<CameraVideoStreamSourceType>>
        get() = _availableLensListData

    val currentLensData: LiveData<CameraVideoStreamSourceType>
        get() = _currentLensData

    val cameraName: LiveData<String>
        get() = _cameraName

    val isVisionAssistEnabled: LiveData<Boolean>
        get() = _isVisionAssistEnabled

    val visionAssistViewDirection: LiveData<VisionAssistDirection>
        get() = _visionAssistViewDirection

    val visionAssistViewDirectionRange: LiveData<List<VisionAssistDirection>>
        get() = _visionAssistViewDirectionRange
}
