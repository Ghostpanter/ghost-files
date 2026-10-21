package com.ghostpanter.files.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/*
 * D 「HyperOS 清爽」 input: soft filled rounded field, no outline and no underline. Drop-in for
 * OutlinedTextField (same parameter list), so every dialog / search / settings field matches.
 */

val GhostFieldShape = RoundedCornerShape(12.dp)

@Composable
fun ghostFieldColors(): TextFieldColors {
    val cs = MaterialTheme.colorScheme
    return TextFieldDefaults.colors(
        focusedContainerColor = cs.surfaceVariant,
        unfocusedContainerColor = cs.surfaceVariant,
        disabledContainerColor = cs.surfaceVariant.copy(alpha = 0.6f),
        errorContainerColor = cs.errorContainer.copy(alpha = 0.35f),
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
        focusedLabelColor = cs.primary,
        unfocusedLabelColor = cs.onSurfaceVariant,
        cursorColor = cs.primary,
        focusedLeadingIconColor = cs.onSurfaceVariant,
        unfocusedLeadingIconColor = cs.onSurfaceVariant,
        focusedTrailingIconColor = cs.onSurfaceVariant,
        unfocusedTrailingIconColor = cs.onSurfaceVariant,
    )
}

@Composable
fun GhostTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = GhostFieldShape,
    colors: TextFieldColors = ghostFieldColors(),
) = TextField(
    value, onValueChange, modifier, enabled, readOnly, textStyle, label, placeholder, leadingIcon, trailingIcon,
    prefix, suffix, supportingText, isError, visualTransformation, keyboardOptions, keyboardActions, singleLine,
    maxLines, minLines, interactionSource, shape, colors,
)

@Composable
fun GhostTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = GhostFieldShape,
    colors: TextFieldColors = ghostFieldColors(),
) = TextField(
    value, onValueChange, modifier, enabled, readOnly, textStyle, label, placeholder, leadingIcon, trailingIcon,
    prefix, suffix, supportingText, isError, visualTransformation, keyboardOptions, keyboardActions, singleLine,
    maxLines, minLines, interactionSource, shape, colors,
)

/** White rounded card container (same look as a file pane) for lists such as the app lists. */
@Composable
fun Modifier.ghostCard(radius: androidx.compose.ui.unit.Dp = 16.dp): Modifier {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(radius)
    return this.clip(shape).background(cs.surface, shape).border(1.dp, cs.outlineVariant, shape)
}

/** Hairline row divider inset past a 40dp icon, used inside [ghostCard] lists. */
@Composable
fun GhostRowDivider(start: androidx.compose.ui.unit.Dp = 68.dp) =
    androidx.compose.material3.HorizontalDivider(Modifier.padding(start = start), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
