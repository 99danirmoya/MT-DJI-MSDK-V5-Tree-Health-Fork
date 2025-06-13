package dji.sampleV5.aircraft.pages

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.cardview.widget.CardView // Changed import from MaterialCardView to CardView

/**
 * Adapter for the RecyclerView to display TreeHealthItem objects.
 * Handles creating views for items and binding data to those views.
 *
 * @param treeHealthList The initial list of TreeHealthItem objects to display.
 */
class TreeHealthAdapter(private var treeHealthList: List<TreeHealthItem>) :
    RecyclerView.Adapter<TreeHealthAdapter.TreeHealthViewHolder>() {

    /**
     * ViewHolder for individual TreeHealthItem views in the RecyclerView.
     * Caches references to the subviews to avoid repeated findViewById calls.
     *
     * @param itemView The root view of the item layout (item_tree_health.xml).
     */
    class TreeHealthViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val treeIdTextView: TextView = itemView.findViewById(dji.sampleV5.aircraft.R.id.tv_tree_id)
        val healthStatusTextView: TextView = itemView.findViewById(dji.sampleV5.aircraft.R.id.tv_health_status)
        // Changed type from MaterialCardView to CardView
        val cardView: CardView = itemView.findViewById(dji.sampleV5.aircraft.R.id.card_tree_health)
    }

    /**
     * Called when RecyclerView needs a new [ViewHolder] of the given type to represent an item.
     *
     * @param parent The ViewGroup into which the new View will be added after it is bound to
     * an adapter position.
     * @param viewType The view type of the new View.
     * @return A new TreeHealthViewHolder that holds a View of the given view type.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TreeHealthViewHolder {
        // Inflate the layout for a single item
        val view = LayoutInflater.from(parent.context)
            .inflate(dji.sampleV5.aircraft.R.layout.item_tree_health, parent, false)
        return TreeHealthViewHolder(view)
    }

    /**
     * Called by RecyclerView to display the data at the specified position.
     * This method updates the contents of the [itemView] to reflect the item at the given position.
     *
     * @param holder The ViewHolder which should be updated to represent the contents of the
     * item at the given position in the data set.
     * @param position The position of the item within the adapter's data set.
     */
    override fun onBindViewHolder(holder: TreeHealthViewHolder, position: Int) {
        val currentItem = treeHealthList[position]

        // Set the text for Tree ID and Health Status
        holder.treeIdTextView.text = "Tree ID: ${currentItem.treeId}"
        holder.healthStatusTextView.text = "Status: ${currentItem.healthStatus}"

        // Change the CardView background color based on health status
        val context = holder.itemView.context
        val color = when (currentItem.healthStatus.trim()) { // Trim to handle potential whitespace
            "Healthy" -> context.getColor(android.R.color.holo_green_light)
            "Sick" -> context.getColor(android.R.color.holo_red_light)
            else -> context.getColor(android.R.color.darker_gray) // Default color for unknown status
        }
        holder.cardView.setCardBackgroundColor(color)
    }

    /**
     * Returns the total number of items in the data set held by the adapter.
     * @return The total number of items in this adapter.
     */
    override fun getItemCount(): Int {
        return treeHealthList.size
    }

    /**
     * Updates the data set of the adapter and notifies the RecyclerView to refresh its views.
     *
     * @param newList The new list of TreeHealthItem objects to display.
     */
    fun updateData(newList: List<TreeHealthItem>) {
        treeHealthList = newList
        notifyDataSetChanged() // A simple way to refresh the entire list. For larger lists, consider DiffUtil.
    }
}
