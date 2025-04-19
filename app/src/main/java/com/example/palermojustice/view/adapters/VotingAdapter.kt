package com.example.palermojustice.view.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.palermojustice.R
import com.example.palermojustice.model.Player

/**
 * Adapter for displaying players in a voting list.
 * Handles selection of a target player for voting or role actions.
 */
class VotingAdapter(
    private var players: List<Player>,
    private val currentPlayerId: String,
    private val onPlayerSelected: (String) -> Unit
) : RecyclerView.Adapter<VotingAdapter.VotingViewHolder>() {

    private var selectedPosition = -1

    inner class VotingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textViewPlayerName: TextView = view.findViewById(R.id.textViewPlayerName)
        val radioButtonSelect: RadioButton = view.findViewById(R.id.radioButtonSelect)

        init {
            // Set click listeners for the entire item
            view.setOnClickListener {
                handleSelection(adapterPosition)
            }

            // Set click listener for the radio button
            radioButtonSelect.setOnClickListener {
                handleSelection(adapterPosition)
            }
        }

        /**
         * Handle selection of a player
         */
        private fun handleSelection(position: Int) {
            // Update previously selected item
            val previousSelected = selectedPosition
            if (previousSelected != -1) {
                selectedPosition = -1
                notifyItemChanged(previousSelected)
            }

            // Update newly selected item
            if (previousSelected != position) {
                selectedPosition = position
                notifyItemChanged(position)

                // Call the callback with the selected player ID
                onPlayerSelected(players[position].id)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VotingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_voting, parent, false)
        return VotingViewHolder(view)
    }

    override fun onBindViewHolder(holder: VotingViewHolder, position: Int) {
        val player = players[position]

        // Set player name
        holder.textViewPlayerName.text = player.name

        // Set selection state
        holder.radioButtonSelect.isChecked = position == selectedPosition

        // Disable voting for self
        if (player.id == currentPlayerId) {
            holder.radioButtonSelect.isEnabled = false
            holder.itemView.isEnabled = false
            holder.textViewPlayerName.text = "${player.name} (You)"
        } else {
            holder.radioButtonSelect.isEnabled = true
            holder.itemView.isEnabled = true
        }
    }

    override fun getItemCount() = players.size

    /**
     * Update the players list and refresh the adapter
     */
    fun updatePlayers(newPlayers: List<Player>) {
        players = newPlayers
        selectedPosition = -1 // Reset selection
        notifyDataSetChanged()
    }
}