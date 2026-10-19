package com.robert4lyf.hypershop.ui.recipes

import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.LocalDarkTheme
import com.robert4lyf.hypershop.ui.theme.card
import java.io.File

private enum class AddMode { CHOOSE, LINK, MANUAL, PHOTO }

/** What "+" on the Recipes tab does: a page's link, or a recipe typed in. */
data class AddRecipeActions(
    val onLink: (String) -> Unit = {},
    /** Name, servings (null when blank) and the ingredient lines as typed. */
    val onManual: (String, Int?, String) -> Unit = { _, _, _ -> },
    /** A photo of a recipe, from the camera or the photo picker, with its book and page (either may be blank). */
    val onPhoto: (Uri, String, String) -> Unit = { _, _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecipeSheet(
    actions: AddRecipeActions,
    onDismiss: () -> Unit,
    hasKey: Boolean = true,
    lastBook: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AddRecipeContent(actions, onDone = onDismiss, hasKey = hasKey, lastBook = lastBook)
    }
}

private const val NEEDS_KEY = "Needs a Claude key"

/** The sheet's content, stateless apart from what is being typed (so tests and screenshots can show it). */
@Composable
fun AddRecipeContent(
    actions: AddRecipeActions,
    onDone: () -> Unit,
    start: String = "CHOOSE",
    /** A Claude key is saved; without one a photo can't be read. */
    hasKey: Boolean = true,
    /** The photo form's book to start with: the one used last. */
    lastBook: String? = null,
) {
    var mode by rememberSaveable { mutableStateOf(start) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (AddMode.valueOf(mode)) {
            AddMode.CHOOSE -> {
                Text("Add a recipe", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Choice(
                    Icons.Rounded.Link,
                    hue = 160f,
                    title = "Paste a link",
                    tag = "add-link",
                ) { mode = AddMode.LINK.name }
                Choice(
                    Icons.Rounded.EditNote,
                    hue = 25f,
                    title = "Type it in",
                    tag = "add-manual",
                ) { mode = AddMode.MANUAL.name }
                Choice(
                    Icons.Rounded.PhotoCamera,
                    hue = 280f,
                    title = "Photo of a recipe",
                    tag = "add-photo",
                    note = NEEDS_KEY.takeIf { !hasKey },
                ) { mode = AddMode.PHOTO.name }
            }
            AddMode.LINK ->
                LinkForm(onBack = { mode = AddMode.CHOOSE.name }) { url ->
                    actions.onLink(url)
                    onDone()
                }
            AddMode.MANUAL ->
                ManualForm(onBack = { mode = AddMode.CHOOSE.name }) { name, serves, lines ->
                    actions.onManual(name, serves, lines)
                    onDone()
                }
            AddMode.PHOTO ->
                PhotoForm(hasKey, lastBook, onBack = { mode = AddMode.CHOOSE.name }) { uri, book, page ->
                    actions.onPhoto(uri, book, page)
                    onDone()
                }
        }
    }
}

@Composable
private fun Choice(
    icon: ImageVector,
    hue: Float,
    title: String,
    tag: String,
    /** Why it won't work yet, shown in the warning colour. */
    note: String? = null,
    onClick: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.card,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Brand.tint(hue, dark)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = Brand.strong(hue, dark)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FormTitle(
    title: String,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun LinkForm(
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    FormTitle("Paste a link", onBack)
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Recipe link") },
        placeholder = { Text("https://…") },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) onSubmit(url) }),
        trailingIcon = {
            // Read only when tapped, so Android shows no "pasted from your clipboard" for nothing.
            IconButton(onClick = {
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { url = it.trim() }
            }) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste") }
        },
        modifier = Modifier.fillMaxWidth().testTag("link-field"),
    )
    Button(
        onClick = { onSubmit(url) },
        enabled = url.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("link-go"),
    ) { Text("Get the ingredients") }
}

/**
 * "Take a photo" hands the camera app a file of ours to save into (no CAMERA
 * permission needed); "Choose from photos" is the system photo picker (none either).
 * The book and page are optional, typed before the photo is taken, and can be
 * changed on the review screen; the book starts as the one used last.
 */
@Composable
private fun PhotoForm(
    hasKey: Boolean,
    lastBook: String?,
    onBack: () -> Unit,
    onPicked: (Uri, String, String) -> Unit,
) {
    val context = LocalContext.current
    var book by rememberSaveable { mutableStateOf(lastBook.orEmpty()) }
    var page by rememberSaveable { mutableStateOf("") }
    val picked = { uri: Uri -> onPicked(uri, book, page) }
    // Where the camera app saves the shot: kept across the trip to the camera and back.
    var shot by rememberSaveable { mutableStateOf<String?>(null) }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = shot?.let(Uri::parse)
            if (ok && uri != null) picked(uri)
        }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(picked) }
    FormTitle("Photo of a recipe", onBack)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = book,
            onValueChange = { book = it },
            label = { Text("Book (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f).testTag("photo-book"),
        )
        OutlinedTextField(
            value = page,
            onValueChange = { page = it.take(20) },
            label = { Text("Page") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.width(96.dp).testTag("photo-page"),
        )
    }
    if (!hasKey) Text(NEEDS_KEY, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
    Button(
        enabled = hasKey,
        onClick = {
            val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "shot.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
            shot = uri.toString()
            // No camera app (rare): nothing happens rather than a crash.
            runCatching { camera.launch(uri) }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("photo-camera"),
    ) {
        Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Take a photo")
    }
    OutlinedButton(
        enabled = hasKey,
        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("photo-pick"),
    ) {
        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Choose from photos")
    }
}

@Composable
private fun ManualForm(
    onBack: () -> Unit,
    onSubmit: (String, Int?, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var serves by rememberSaveable { mutableStateOf("") }
    var lines by rememberSaveable { mutableStateOf("") }
    val servings = serves.trim().toIntOrNull()?.takeIf { it in 1..99 }
    val badServes = serves.isNotBlank() && servings == null
    FormTitle("Type it in", onBack)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Recipe name") },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("manual-name"),
    )
    OutlinedTextField(
        value = serves,
        onValueChange = { serves = it.filter(Char::isDigit).take(2) },
        label = { Text("Serves (optional)") },
        isError = badServes,
        supportingText = if (badServes) ({ Text("Between 1 and 99") }) else null,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("manual-serves"),
    )
    OutlinedTextField(
        value = lines,
        onValueChange = { lines = it },
        label = { Text("Ingredients, one per line") },
        placeholder = { Text("400 g chopped tomatoes\n2 onions\n1 tbsp olive oil") },
        minLines = 6,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().testTag("manual-lines"),
    )
    Button(
        onClick = { onSubmit(name.trim(), servings, lines) },
        enabled = name.isNotBlank() && lines.isNotBlank() && !badServes,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("manual-go"),
    ) { Text("Review ingredients") }
}
