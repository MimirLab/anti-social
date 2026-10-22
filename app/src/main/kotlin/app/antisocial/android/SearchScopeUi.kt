package app.antisocial.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.*

/** One compact entry point; platform-specific scope fields stay in the sheet. */
@Composable internal fun SearchScopeControl(
    platform: Platform,
    scope: SearchScope?,
    onScope: (SearchScope?)->Unit,
    modifier: Modifier=Modifier,
) {
    val options=remember(platform){SearchScopes.options(platform)}
    if(options.isEmpty())return
    var expanded by remember(platform){mutableStateOf(false)}
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val label=SearchScopes.label(platform,scope)
    Surface(
        onClick={focus.clearFocus(force=true);keyboard?.hide();expanded=true},shape=RoundedCornerShape(24.dp),
        color=if(scope==null)MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.65f)else MaterialTheme.colorScheme.secondaryContainer,
        contentColor=if(scope==null)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier=modifier.testTag("Search scope").semantics {contentDescription="Search within: $label"},
    ) {
        Row(Modifier.defaultMinSize(minHeight=40.dp).padding(start=12.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
            if(scope==null){Icon(Icons.Rounded.Public,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp))}
            Text(label,fontSize=12.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=160.dp))
            Icon(Icons.Rounded.ArrowDropDown,null,Modifier.size(20.dp))
        }
    }
    if(expanded)SearchScopeSheet(platform,scope,onDismiss={expanded=false},onScope={keyboard?.hide();onScope(it);expanded=false})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SearchScopeSheet(platform: Platform,scope: SearchScope?,onDismiss: ()->Unit,onScope: (SearchScope?)->Unit) {
    val options=remember(platform){SearchScopes.options(platform)}
    var kind by remember(platform){mutableStateOf(scope?.kind)}
    var value by remember(platform){mutableStateOf(scope?.value.orEmpty())}
    var error by remember(platform){mutableStateOf<String?>(null)}
    val selected=options.firstOrNull {it.kind==kind}
    ModalBottomSheet(
        onDismissRequest=onDismiss,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=MaterialTheme.colorScheme.surface,
        modifier=Modifier.testTag("Search scope sheet"),
    ) {
        // A modal sheet owns a different window from its opener. Use its focus and IME
        // controller, otherwise the dismissed editor can leave the feed behind a keyboard.
        val focus=LocalFocusManager.current
        val keyboard=LocalSoftwareKeyboardController.current
        val commit: (SearchScope?)->Unit={focus.clearFocus(force=true);keyboard?.hide();onScope(it)}
        val dismiss={focus.clearFocus(force=true);keyboard?.hide();onDismiss()}
        val apply={
            if(kind==null)commit(null)
            else try {commit(SearchScopes.normalize(platform,requireNotNull(kind),value))}catch(e: IllegalArgumentException){error=e.message ?: "Enter a valid scope"}
        }
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=24.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Search within",fontSize=24.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                IconButton(onClick=dismiss){Icon(Icons.Rounded.Close,"Close search scopes")}
            }
            Text("Narrow your ${platform.label} search.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=5.dp,bottom=18.dp))
            ScopeOption("Everywhere","All public ${platform.label} results",kind==null,"Search scope everywhere") {commit(null)}
            options.forEach {option->
                ScopeOption(option.label,option.hint,kind==option.kind,"Search scope option ${option.kind.name}") {
                    if(kind!=option.kind){kind=option.kind;value=scope?.takeIf {it.kind==option.kind}?.value.orEmpty()}
                    error=null
                }
            }
            if(selected!=null) {
                OutlinedTextField(
                    value=value,onValueChange={value=it;error=null},label={Text(selected.label)},
                    placeholder={Text(selected.hint)},singleLine=true,isError=error!=null,
                    supportingText=if(error==null)null else ({Text(error.orEmpty())}),
                    modifier=Modifier.fillMaxWidth().padding(top=14.dp).testTag("Search scope value"),
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={apply()}),
                )
                Button(onClick={apply()},enabled=value.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(top=14.dp).testTag("Apply search scope")) {Text("Apply scope")}
            }
        }
    }
}

@Composable private fun ScopeOption(title: String,subtitle: String,selected: Boolean,tag: String,onClick: ()->Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(tag).selectable(selected=selected,role=Role.RadioButton,onClick=onClick).padding(vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,fontSize=15.sp)
            Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=2.dp))
        }
        if(selected)Icon(Icons.Rounded.Check,"Selected",Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
    }
}

/** Avoid a permanent strip containing every supported platform in the search dialog. */
@Composable internal fun SearchPlatformPicker(platform: Platform,onPlatform: (Platform)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Box {
        TextButton(onClick={expanded=true},contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.testTag("Search platform")) {
            PlatformMark(platform,18.dp)
            Text(platform.label,Modifier.padding(start=7.dp),maxLines=1)
            Icon(Icons.Rounded.ArrowDropDown,null,Modifier.size(18.dp))
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=360.dp).testTag("Search platform menu")) {
            PlatformOrder.popular.forEach {candidate->
                DropdownMenuItem(text={Text(candidate.label)},leadingIcon={PlatformMark(candidate,20.dp)},
                    trailingIcon={if(candidate==platform)Icon(Icons.Rounded.Check,null,Modifier.size(18.dp))},
                    onClick={onPlatform(candidate);expanded=false},modifier=Modifier.testTag("Search platform ${candidate.id}"))
            }
        }
    }
}
