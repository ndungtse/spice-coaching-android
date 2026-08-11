package com.medtroniclabs.microcoaching.ui.trainingrequest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.common.translatedText
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

/**
 * Training-request form: intro description, module selector (bottom-sheet
 * picker with a "suggest a new module" free-text escape hatch), optional
 * reason, online-only submit with inline error feedback.
 *
 * @param onSubmitted Called after a successful submit — the NavGraph pops back
 *   and shows the confirmation on the shared snackbar.
 */
@Composable
fun TrainingRequestFormScreen(
    chwId: String,
    onSubmitted: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    val vm: TrainingRequestFormViewModel = viewModel(
        factory = TrainingRequestFormViewModel.factory(chwId),
    )
    val uiState by vm.uiState.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is TrainingRequestFormEvent.Submitted -> onSubmitted()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(SurfaceMuted)) {
        SdkScreenHeader(
            title = stringResource(R.string.training_request_form_title),
            onBack = onBack,
            onHome = onHome,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            FormDescriptionCard()

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.training_request_module_label),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))
            val customTitle = uiState.customModuleTitle
            if (customTitle != null) {
                CustomModuleTitleField(
                    value = customTitle,
                    onValueChange = vm::updateCustomModuleTitle,
                    onClear = vm::exitCustomModuleMode,
                )
            } else {
                ModuleSelectorField(
                    selected = uiState.selectedModule,
                    onClick = { showPicker = true },
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.training_request_reason_label),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = uiState.reason,
                onValueChange = vm::updateReason,
                placeholder = { Text(stringResource(R.string.training_request_reason_hint)) },
                minLines = 4,
                maxLines = 8,
                supportingText = {
                    Text(
                        text = "${uiState.reason.length}/$TRAINING_REQUEST_REASON_MAX_CHARS",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SpiceBlue,
                    unfocusedContainerColor = Color.White,
                    focusedContainerColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding(),
        ) {
            uiState.errorRes?.let { errorRes ->
                Text(
                    text = stringResource(errorRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            Button(
                onClick = vm::submit,
                enabled = uiState.canSubmit,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.submitting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.training_request_submit),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        }
    }

    if (showPicker) {
        ModulePickerBottomSheet(
            modules = uiState.modules,
            onSelect = { item ->
                vm.selectModule(item)
                showPicker = false
            },
            onSuggestNew = { query ->
                vm.enterCustomModuleMode(prefill = query)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * Intro blurb explaining what the form does. Replaces the earlier
 * "Requested by" identity card — the backend stamps the requester
 * server-side, so showing the CHW their own id/name added nothing.
 */
@Composable
private fun FormDescriptionCard() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(SpiceBlueContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.School,
            contentDescription = null,
            tint = SpiceBlue,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.padding(start = 12.dp))
        Text(
            text = stringResource(R.string.training_request_form_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun ModuleSelectorField(
    selected: ModulePickerItem?,
    onClick: () -> Unit,
) {
    Box {
        OutlinedTextField(
            value = selected?.let { translatedText(it.title) }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            placeholder = { Text(stringResource(R.string.training_request_module_placeholder)) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = SpiceBlue,
                )
            },
            // Disabled so the read-only field never grabs focus/IME; restyled to
            // read as an active selector (the overlay Box handles the tap).
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onBackground,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                disabledContainerColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onClick),
        )
    }
}

/**
 * Free-text topic input for the "suggest a new module" mode. The trailing
 * close icon returns to the catalogue picker.
 */
@Composable
private fun CustomModuleTitleField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.training_request_custom_hint)) },
        trailingIcon = {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.training_request_custom_clear),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        },
        supportingText = {
            Text(
                text = stringResource(R.string.training_request_custom_supporting),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SpiceBlue,
            unfocusedContainerColor = Color.White,
            focusedContainerColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModulePickerBottomSheet(
    modules: List<ModulePickerItem>,
    onSelect: (ModulePickerItem) -> Unit,
    onSuggestNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(modules, query) { modules.filterByQuery(query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text(
                text = stringResource(R.string.training_request_picker_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.training_request_picker_search_hint)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SpiceBlue),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            // Always-visible escape hatch into "suggest a new module" mode,
            // carrying the current search text as the prefill. Given a filled
            // pill background so it reads as an action, distinct from the module
            // rows below it.
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SpiceBlueContainer)
                    .clickable { onSuggestNew(query.trim()) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = SpiceBlue,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.padding(start = 8.dp))
                Text(
                    text = stringResource(R.string.training_request_picker_suggest_new),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = SpiceBlue,
                )
            }
            Spacer(Modifier.height(4.dp))
            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.training_request_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                    if (query.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onSuggestNew(query.trim()) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.training_request_picker_suggest_query,
                                    query.trim(),
                                ),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 8.dp, end = 8.dp, bottom = 24.dp,
                    ),
                ) {
                    items(filtered, key = { it.moduleId }) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(item) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = translatedText(item.title),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.domain.replace('_', ' ').uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}
