package com.sigeye.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** How much of the reading is in trouble. */
enum class StepTone {
    /** There is no reading. Nothing on the screen below this means anything yet. */
    STUCK,

    /** There is a reading and it is probably not worth believing. */
    SHAKY,

    /** It worked, and there is one thing that would make the next one better. */
    BETTER,
}

/**
 * A reading that did not work, and the thing to do about it.
 *
 * [problem] is what went wrong, in the reader's terms rather than the code's. [doThis] is
 * an instruction they can carry out where they are standing, now, without leaving the
 * screen to go and read something.
 */
data class NextStep(
    val problem: String,
    val doThis: String,
    val tone: StepTone = StepTone.STUCK,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/**
 * What to do when the reading is bad.
 *
 * Every experiment here can fail, and most of them failed by showing an empty list, a dash,
 * or a number quietly built on four packets. That is the moment somebody decides the app is
 * broken, and it is also the moment the app knows exactly what went wrong and exactly what
 * would fix it - it just never said.
 *
 * Follow Me's guided flow proved the pattern: "the target probably changed address partway
 * through, walk another leg" instead of an empty shortlist. This is that, shared, so a
 * screen turns its verdict into a next action by returning a [NextStep] rather than by
 * inventing its own card.
 *
 * Renders nothing at all when there is nothing to say, so a screen can pass the result of a
 * check straight in without wrapping it in a condition.
 */
@Composable
fun NextStepCard(step: NextStep?, modifier: Modifier = Modifier) {
    if (step == null) return

    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (step.tone) {
                StepTone.STUCK -> MaterialTheme.colorScheme.errorContainer
                StepTone.SHAKY -> MaterialTheme.colorScheme.surfaceVariant
                StepTone.BETTER -> MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                step.problem,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(step.doThis, style = MaterialTheme.typography.bodySmall)

            val action = step.action
            val label = step.actionLabel
            if (action != null && label != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
            }
        }
    }
}
