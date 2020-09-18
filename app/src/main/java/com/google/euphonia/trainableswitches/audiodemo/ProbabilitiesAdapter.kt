package com.google.euphonia.trainableswitches.audiodemo

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.google.euphonia.trainableswitches.audiodemo.databinding.ItemProbabilityBinding

internal class ProbabilitiesAdapter : RecyclerView.Adapter<ProbabilitiesAdapter.ViewHolder>() {
    var labelList = emptyList<String>()
    var probabilityMap = mapOf<String, Float>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemProbabilityBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val label = labelList[position]
        val probability = probabilityMap[label] ?: 0f

        with(holder.binding) {
            labelTextView.text = label
            progressBar.progressBackgroundTintList = progressColorPairList[position % 3].first
            progressBar.progressTintList = progressColorPairList[position % 3].second

            val newValue = (probability * 100).toInt()
            // If you don't want to animate, you can write like `progressBar.progress = newValue`.
            val animation =
                ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, newValue)
            animation.duration = 100
            animation.interpolator = AccelerateDecelerateInterpolator()
            animation.start()
        }
    }

    override fun getItemCount() = labelList.size

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
