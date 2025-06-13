package dji.sampleV5.aircraft.pages

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.commit // Required import for fragment transactions
import com.google.gson.Gson
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.views.MSDKInfoFragment // Assuming this is your base class, adjust if needed
import dji.sampleV5.aircraft.AircraftMSDKInfoFragment // Import the specific fragment for the banner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Activity to display the Thesis Tree Report, now integrating with ThingsBoard to fetch attributes
 * and display them in a RecyclerView, and also including the common DJI MSDK banner.
 */
class ThesisTreeReport : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var treeHealthAdapter: TreeHealthAdapter

    private val client = OkHttpClient()
    private val gson = Gson()

    // IMPORTANT: Replace with your actual ThingsBoard Base URL
    private val THINGSBOARD_BASE_URL = "https://srv-iot.diatel.upm.es"

    // IMPORTANT: Replace with the actual Device Access Token for the device ID b1b30240-4706-11f0-a5ae-8b4b746ddfd4
    private val DEVICE_ACCESS_TOKEN = "mSr7rBY10rYYUv2La6aC"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_thesis_tree_report) // Use your layout file

        // Load the banner fragment into the banner_container
        loadBannerView()

        // Initialize RecyclerView and its adapter
        recyclerView = findViewById(R.id.rv_tree_health)
        recyclerView.layoutManager = LinearLayoutManager(this)
        treeHealthAdapter = TreeHealthAdapter(emptyList())
        recyclerView.adapter = treeHealthAdapter

        // Fetch the report from ThingsBoard when the activity is created
        fetchThingsBoardReport()
    }

    /**
     * Loads the AircraftMSDKInfoFragment into the banner_container.
     * This fragment is responsible for displaying the common DJI MSDK banner.
     */
    private fun loadBannerView() {
        // Use the supportFragmentManager to add AircraftMSDKInfoFragment
        // into the banner_container defined in your activity's layout.
        supportFragmentManager.commit {
            replace(R.id.banner_container, AircraftMSDKInfoFragment())
        }
    }

    /**
     * Fetches the "report" SHARED_SCOPE attribute from ThingsBoard for a specific device.
     * Parses the comma-separated health status string and populates the RecyclerView.
     */
    private fun fetchThingsBoardReport() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Construct the URL for fetching shared attributes using the device access token
                val url = "$THINGSBOARD_BASE_URL/api/v1/$DEVICE_ACCESS_TOKEN/attributes?sharedKeys=report"

                // Build the HTTP request
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json") // Request JSON response
                    .build()

                // Execute the request and get the response
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        // Parse the JSON response.
                        // Expected format: {"shared": {"report":"Healthy,Sick,Healthy,..."}}
                        val attributesResponse = gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
                        val sharedAttributes = attributesResponse["shared"] as? Map<String, Any>
                        val reportContent = sharedAttributes?.get("report") as? String

                        withContext(Dispatchers.Main) {
                            if (!reportContent.isNullOrBlank()) {
                                // Split the health status string by comma
                                val healthStatuses = reportContent.split(",").filter { it.isNotBlank() }
                                val treeHealthItems = mutableListOf<TreeHealthItem>()

                                // Create TreeHealthItem objects with incrementing treeId
                                healthStatuses.forEachIndexed { index, status ->
                                    treeHealthItems.add(TreeHealthItem(index, status.trim()))
                                }

                                // Update the RecyclerView adapter with the new data
                                if (treeHealthItems.isNotEmpty()) {
                                    treeHealthAdapter.updateData(treeHealthItems)
                                    Toast.makeText(this@ThesisTreeReport, "Tree report loaded successfully.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@ThesisTreeReport, "No tree health data found in the report.", Toast.LENGTH_LONG).show()
                                    treeHealthAdapter.updateData(emptyList()) // Clear the RecyclerView
                                }
                            } else {
                                Toast.makeText(this@ThesisTreeReport, "Report attribute is empty or not found in ThingsBoard.", Toast.LENGTH_LONG).show()
                                treeHealthAdapter.updateData(emptyList()) // Clear the RecyclerView
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ThesisTreeReport, "ThingsBoard response body is null.", Toast.LENGTH_LONG).show()
                            treeHealthAdapter.updateData(emptyList())
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        val errorMessage = "Failed to fetch report: ${response.code} ${response.message}"
                        Toast.makeText(this@ThesisTreeReport, errorMessage, Toast.LENGTH_LONG).show()
                        treeHealthAdapter.updateData(emptyList()) // Clear the RecyclerView on error
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    val errorMessage = "Network error: ${e.message}"
                    Toast.makeText(this@ThesisTreeReport, errorMessage, Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                    treeHealthAdapter.updateData(emptyList()) // Clear the RecyclerView on error
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMessage = "An unexpected error occurred: ${e.message}"
                    Toast.makeText(this@ThesisTreeReport, errorMessage, Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                    treeHealthAdapter.updateData(emptyList()) // Clear the RecyclerView on error
                }
            }
        }
    }
}
