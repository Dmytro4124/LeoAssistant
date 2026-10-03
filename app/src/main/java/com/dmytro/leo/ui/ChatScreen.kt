package com.dmytro.leo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dmytro.leo.R
import com.dmytro.leo.brain.AgentConfig
import com.dmytro.leo.ui.theme.LeoTheme

/** Text chat with the agent. Stays in the app as a debug mode for testing commands without voice. */
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatContent(state = state, onSend = viewModel::send, onClear = viewModel::clear)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Keep the newest message in view
    LaunchedEffect(state.messages.size, state.isThinking) {
        val count = state.messages.size + if (state.isThinking) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun submit() {
        if (input.isNotBlank() && !state.isThinking) {
            onSend(input)
            input = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(AgentConfig.NAME) },
                actions = {
                    TextButton(onClick = onClear, enabled = state.messages.isNotEmpty() && !state.isThinking) {
                        Text("Очистити")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Повідомлення…") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                FilledIconButton(
                    onClick = ::submit,
                    enabled = input.isNotBlank() && !state.isThinking,
                ) {
                    Icon(painterResource(R.drawable.ic_send), contentDescription = "Надіслати")
                }
            }
        },
    ) { innerPadding ->
        if (state.messages.isEmpty() && !state.isThinking) {
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Напиши щось, наприклад:\n«Привіт, що ти вмієш?»",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
                if (state.isThinking) {
                    item(key = "thinking") {
                        MessageBubble(ChatMessage(-1, ChatMessage.Author.Agent, "${AgentConfig.NAME} думає…"))
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.author == ChatMessage.Author.User
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (message.author) {
        ChatMessage.Author.User -> colors.primaryContainer to colors.onPrimaryContainer
        ChatMessage.Author.Agent -> colors.surfaceContainerHigh to colors.onSurface
        ChatMessage.Author.Error -> colors.errorContainer to colors.onErrorContainer
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = background,
            contentColor = foreground,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            // Selectable, so replies can be copied while debugging
            SelectionContainer {
                Text(message.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatPreview() {
    LeoTheme(dynamicColor = false) {
        ChatContent(
            state = ChatUiState(
                messages = listOf(
                    ChatMessage(0, ChatMessage.Author.User, "Привіт, що ти вмієш?"),
                    ChatMessage(1, ChatMessage.Author.Agent, "Привіт! Я Лео. Поки що відповідаю на запитання, а скоро навчуся керувати телефоном."),
                    ChatMessage(2, ChatMessage.Author.Error, "Немає з'єднання з інтернетом."),
                ),
                isThinking = true,
            ),
            onSend = {},
            onClear = {},
        )
    }
}
