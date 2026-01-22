package ro.pub.cs.systems.eim.practicaltest02v09

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.text.ifEmpty

data class CachedData(val value: String, val timestamp: Long)

class PracticalTest02v09MainActivity : AppCompatActivity() {
    lateinit var portServerEditText : EditText
    lateinit var portClientEditText : EditText
    lateinit var addressEditText : EditText

    lateinit var wordEditText : EditText
    lateinit var lenEditText : EditText

    lateinit var searchButton : Button
    lateinit var connectButton : Button

    lateinit var resultTextView : TextView

    private var serverThread : ServerThread? = null

    private val dataCache = HashMap<String, CachedData>()

    val BROADCAST_ACTION = "ro.pub.cs.systems.eim.practicaltest02v9.BROADCAST"

    val filter = IntentFilter(BROADCAST_ACTION)

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d("[BROADCAST]", "Broadcast received!")

            val result = intent.getStringExtra("result_key")
            resultTextView.text = result
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        setContentView(R.layout.activity_practical_testv09_main)

        portServerEditText = findViewById(R.id.portServerEditText)
        portClientEditText = findViewById(R.id.portClientEditText)
        addressEditText = findViewById(R.id.addressEditText)

        wordEditText = findViewById(R.id.edit_text_word)
        lenEditText = findViewById(R.id.edit_text_len)

        searchButton = findViewById(R.id.button_send)
        connectButton = findViewById(R.id.connectButton)

        resultTextView = findViewById(R.id.text_view_result)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(messageReceiver, filter)
        }

        connectButton.setOnClickListener {
            val portText = portServerEditText.text.toString()
            if (portText.isNotEmpty()) {
                val port = portText.toInt()
                serverThread = ServerThread(port)
                serverThread?.start()
                Toast.makeText(this, "Server started on port $port", Toast.LENGTH_SHORT).show()
            }
        }

        searchButton.setOnClickListener {
            val word = wordEditText.text.toString()
            val len = lenEditText.text.toString()
            val portText = portClientEditText.text.toString()

            if (word.isNotEmpty() && len.isNotEmpty() && portText.isNotEmpty()) {
                val port = portText.toInt()
                ClientThread(word, len, port).start()
            } else {
                Toast.makeText(this, "Completeaza toate campurile", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class ClientThread(private val word: String, private val len: String, private val port: Int) : Thread() {
        override fun run() {
            try {
                val address = if (::addressEditText.isInitialized) addressEditText.text.toString().trim() else "localhost"
                val socket = Socket(address, port)

                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println("$word $len")

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val response = reader.readText()

                runOnUiThread {
                    val intent = Intent(BROADCAST_ACTION)
                    intent.putExtra("result_key", response.ifEmpty { "No response" })
                    sendBroadcast(intent)
                }

                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    resultTextView.text = "Error: ${e.message}"
                }
            }
        }
    }

    inner class ServerThread(private val port: Int) : Thread() {
        private var serverSocket: ServerSocket? = null
        private var isRunning = true

        override fun run() {
            try {
                serverSocket = ServerSocket(port)
                Log.d("[Server]", "Waiting for connections...")

                while (isRunning) {
                    val socket = serverSocket?.accept()
                    Log.d("[Server]", "Client connected")

                    if (socket != null) {
                        CommunicationThread(socket).start()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stopServer() {
            isRunning = false
            try {
                serverSocket?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    inner class CommunicationThread(private val socket: Socket) : Thread() {
        override fun run() {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine()

                Log.d("[Server]", "Received request: $requestLine")

                val parts = requestLine.split(" ")
                val word = parts[0]
                val minLen = if (parts.size > 1) parts[1].toInt() else 0

                var resultValue = ""
                val currentTime = System.currentTimeMillis()

                synchronized(dataCache) {
                    if (dataCache.containsKey(word)) {
                        val cachedData = dataCache[word]!!
                        if (currentTime - cachedData.timestamp < 10000) {
                            Log.d("[Server]", "CACHE HIT for $word")

                            resultValue = cachedData.value
                        } else {
                            Log.d("[Server]", "CACHE EXPIRED for $word")
                        }
                    } else {
                        Log.d("[Server]", "CACHE MISS for $word")
                    }
                }

                if (resultValue.isEmpty()) {
                    resultValue = fetchAnagramsFromApi(word, minLen)

                    if (!resultValue.startsWith("Error")) {
                        synchronized(dataCache) {
                            dataCache[word] = CachedData(resultValue, System.currentTimeMillis())
                        }
                    }
                }

                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(resultValue)
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun fetchAnagramsFromApi(word: String, minLength: Int): String {
            return try {
                val urlString = "http://www.anagramica.com/all/$word"

                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(urlString)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        val content = response.body!!.string()
                        Log.i("[ServerThread]", "Raw: $content")

                        val jsonObject = JSONObject(content)
                        val allAnagrams = jsonObject.getJSONArray("all")

                        val filteredList = ArrayList<String>()
                        for (i in 0 until allAnagrams.length()) {
                            val anagram = allAnagrams.getString(i)
                            if (anagram.length >= minLength) {
                                filteredList.add(anagram)
                            }
                        }

                        return filteredList.joinToString(separator = "\n")
                    } else {
                        Log.e("[ServerThread]", "Cererea nu a avut succes. Cod: ${response.code}")
                        return "Error: API call failed"
                    }
                }
            } catch (e: Exception) {
                Log.e("[ServerThread]", "API Error: ${e.message}")
                return "Error: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(messageReceiver)
        serverThread?.stopServer()
    }
}