package cityu.ai510.TLDR
import android.content.Context
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cityu.ai510.TLDR.ui.theme.MyApplicationTheme
import com.darkrockstudios.libraries.mpfilepicker.FilePicker
import com.darkrockstudios.libraries.mpfilepicker.MPFile
import com.google.gson.Gson
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

enum class State {
    NO_FILE, UPLOADING_FILE, GENERATING_SUMMARY, SUMMARY_READY
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            MyApplicationTheme {
                val state = remember { mutableStateOf(State.NO_FILE) }
                val showFilePicker = remember { mutableStateOf(false) }
                val summary = remember { mutableStateOf("") }
                val context = LocalContext.current
                Surface(modifier = Modifier.fillMaxSize()) {
                    DrawUI(state, summary)
                    TLDRButton(showFilePicker)
                    ShowFilePicker(context, state, showFilePicker, summary)
                }
            }
        }
    }
}

@Composable
fun TLDRButton(showFilePicker: MutableState<Boolean>) {
    val configuration = LocalConfiguration.current

    val screenHeight = configuration.screenHeightDp.dp
    val buttonHeight = 70.dp
    val buttonWidth = 300.dp

    ExtendedFloatingActionButton(onClick = {
        showFilePicker.value = true
    },
        icon = { Icon(Icons.Rounded.Send, "Extended floating action button.", tint = Color.White) },
        text = { Text("Upload & Summarize",
            textAlign = TextAlign.Center,
            fontSize = 20.sp,
            color = Color.White)
               },
        modifier = Modifier
            .requiredWidth(buttonWidth)
            .requiredHeight(buttonHeight)
            .offset(
                y = screenHeight.value
                    .times(0.6)
                    .minus(screenHeight.value.div(2)).dp
            ),
        containerColor = Color(0xFFE3A34D))
}

@Composable
fun ShowFilePicker(
    context: Context,
    state: MutableState<State>,
    showFilePicker: MutableState<Boolean>,
    summary: MutableState<String>
) {
    val fileType = listOf("pdf")

    FilePicker(show = showFilePicker.value, fileExtensions = fileType) { platformFile ->
        showFilePicker.value = false
        state.value = State.UPLOADING_FILE
        if (platformFile == null) {
            state.value = State.NO_FILE
        } else {
            AsyncAPITask(summary, context, state).execute(platformFile)
        }

    }
}

fun extractTextFromPDF(platformFile: MPFile<Any>,context: Context,
                       state: MutableState<State>) : String {
    var textContent = ""
    val uri = Uri.parse(platformFile.path)
    val stream = context.contentResolver.openInputStream(uri)
    if (stream != null) {
        val bytes = stream.readBytes()
        stream.close()
        val pdfDocument = PDDocument.load(bytes)
        // Extract text from the PDF
        val pdfTextStripper = PDFTextStripper()
        textContent = pdfTextStripper.getText(pdfDocument)
        pdfDocument.close()
        state.value = State.GENERATING_SUMMARY
    } else {
        state.value = State.NO_FILE
    }
    return textContent
}

fun callSummarizeAPI(pdf_text: String, context: Context): String {
    var res = "An error has occured while attempting to summarize"
    val url = "https://ai510-bart-tp04.westus.inference.ml.azure.com/score"
    val api_key = ApiKey.KEY

    val data = mapOf("content" to pdf_text)
    val headers = mapOf(
        "Content-Type" to "application/json",
        "Authorization" to "Bearer $api_key"
    )

    try {
        val response = with(context) {
            val client = OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS) // Set a longer connection timeout (adjust as needed)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val jsonBody = Gson().toJson(data).toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url)
                .headers(headers.toHeaders())
                .post(jsonBody)
                .build()
            client.newCall(request).execute()
        }

        if (response.isSuccessful) {
            val responseBody = response.body!!.string()
            val extractedData = Gson().fromJson(responseBody, Map::class.java)
            res = extractedData["summary"].toString()
        } else {
            val error = "Request failed with status code ${response.code}"
            // Handle request failure
            Log.e("summary-api", error)
        }
    } catch (e: Exception) {
        // Handle network exceptions
        e.printStackTrace()
    }
    Log.e("res", res)
    return res;
}

private class AsyncAPITask(private val summary: MutableState<String>, private val context: Context,private val state: MutableState<State>) : AsyncTask<MPFile<Any>, Void, String>() {
    override fun doInBackground(vararg pdf: MPFile<Any>): String {
        return callSummarizeAPI(extractTextFromPDF(pdf.get(0), context, state), context)
    }

    override fun onPostExecute(sb: String) {
        summary.value = sb
        state.value = State.SUMMARY_READY
    }
}

@Composable
fun DrawUI(state: MutableState<State>, summary: MutableState<String>) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.6f)
            .background(color = Color(0xFF1B2951))
            .padding(20.dp)) {
            UploadUI()
        }
        Column(modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            SummaryUI(state, summary)
        }
    }
}

@Composable
fun UploadUI() {
    Row(modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_upload_file),
                contentDescription = "",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .wrapContentSize()
                    .width(150.dp)
                    .height(150.dp)
            )
            Spacer(Modifier.size(20.dp))
            Text("You need to upload your",
                textAlign = TextAlign.Center,
                fontSize = 20.sp,
                color = Color.White)
            Spacer(Modifier.size(10.dp))
            Text("PDF file",
                textAlign = TextAlign.Center,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White)
        }
    }
}

@Composable
fun SummaryUI(state: MutableState<State>, summary: MutableState<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight(), verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.value != State.SUMMARY_READY) {
                if (state.value == State.NO_FILE) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_summarize_48dp),
                        contentDescription = "",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .wrapContentSize()
                            .width(70.dp)
                            .height(70.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(70.dp)
                            .height(70.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = Color(0xFF1B2951),
                    )
                }

                Spacer(Modifier.size(10.dp))
            }
            Text(
                text = when (state.value) {
                    State.UPLOADING_FILE -> "File is being uploading ..."
                    State.GENERATING_SUMMARY -> "Generating summary from file ..."
                    State.SUMMARY_READY -> summary.value
                    else -> "Summary will show up here!"
                },
                modifier = Modifier.width(500.dp)
                    .padding(top = when (state.value) {
                        State.SUMMARY_READY -> 40.dp
                        else -> 0.dp
                    })
                    .verticalScroll(rememberScrollState()),
                    textAlign = TextAlign.Start,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1B2951)
                        )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        val state = remember { mutableStateOf(State.UPLOADING_FILE) }
        val showFilePicker = remember { mutableStateOf(false) }
        val summary = remember {mutableStateOf("")}
        DrawUI(state, summary)
        TLDRButton(showFilePicker)
    }
}