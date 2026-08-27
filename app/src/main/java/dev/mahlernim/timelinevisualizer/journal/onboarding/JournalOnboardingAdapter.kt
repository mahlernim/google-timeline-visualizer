package dev.mahlernim.timelinevisualizer.journal.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.mahlernim.timelinevisualizer.databinding.ItemJournalOnboardingPageBinding

class JournalOnboardingAdapter(
    private val pages: List<JournalOnboardingPage>,
) : RecyclerView.Adapter<JournalOnboardingAdapter.PageViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder = PageViewHolder(
        ItemJournalOnboardingPageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) = holder.bind(pages[position])

    override fun getItemCount(): Int = pages.size

    class PageViewHolder(
        private val binding: ItemJournalOnboardingPageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(page: JournalOnboardingPage) {
            binding.onboardingIllustration.illustration = page.illustration
            binding.onboardingPageTitle.setText(page.titleRes)
            binding.onboardingPageBody.setText(page.bodyRes)
            binding.onboardingPageNote.visibility = if (page.noteRes == null) View.GONE else View.VISIBLE
            page.noteRes?.let(binding.onboardingPageNote::setText)
        }
    }
}
