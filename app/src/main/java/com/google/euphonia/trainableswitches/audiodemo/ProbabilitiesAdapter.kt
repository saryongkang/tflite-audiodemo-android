package com.google.euphonia.trainableswitches.audiodemo

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.euphonia.trainableswitches.audiodemo.databinding.ItemProbabilityBinding
import java.util.*

class ProbabilitiesAdapter : RecyclerView.Adapter<ProbabilitiesAdapter.ViewHolder>() {
    var probabilityList = emptyList<Probability>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemProbabilityBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (className, probability) = probabilityList[position]
        with(holder) {
            binding.labelTextView.text = className.toTitleCase()
            binding.progressBar.progressBackgroundTintList =
                progressColorPairList[position % 3].first
            binding.progressBar.progressTintList = progressColorPairList[position % 3].second
            binding.progressBar.progress = (probability * 100).toInt()
        }
    }

    override fun getItemCount() = probabilityList.size

    class ViewHolder(val binding: ItemProbabilityBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        /** List of pairs of background tint and progress tint */
        val progressColorPairList = listOf(
            ColorStateList.valueOf(0xfff9e7e4.toInt()) to ColorStateList.valueOf(0xffd97c2e.toInt()),
            ColorStateList.valueOf(0xfff7e3e8.toInt()) to ColorStateList.valueOf(0xffc95670.toInt()),
            ColorStateList.valueOf(0xffecf0f9.toInt()) to ColorStateList.valueOf(0xff714Fe7.toInt()),
        )
    }
}

private fun String.toTitleCase() =
    splitToSequence("_")
        .map { it.capitalize(Locale.ROOT) }
        .joinToString(" ")
        .trim()