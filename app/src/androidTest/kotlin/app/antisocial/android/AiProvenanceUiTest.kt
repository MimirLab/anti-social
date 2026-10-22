/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** These fixtures exercise native presentation only; they do not contact a metadata service. */
@RunWith(AndroidJUnit4::class)
class AiProvenanceUiTest {
    @get:Rule val ui=createComposeRule()

    private val generation=AiDisclosureEntry(
        source="Bluesky",asset="Image 1",
        paragraphs=listOf("This image is declared as created using generative AI.","The supplied history also records a crop after creation."),
        evidence="Embedded Content Credentials",verification="The signature and this file match. The signer is not on a configured trust list.",
        tools=listOf("Example Image Studio 4.2"),aiDeclared=true)

    private fun show(entries: List<AiDisclosureEntry>,dark: Boolean=true) {
        ui.setContent {
            MaterialTheme(colorScheme=if(dark)darkColorScheme()else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("Workshop notebook",style=MaterialTheme.typography.titleLarge)
                        Text("A small model of our community repair workshop, shared across two sites.",style=MaterialTheme.typography.bodyLarge)
                        AiProvenanceDisclosure(entries)
                    }
                }
            }
        }
    }

    private fun evidence(name: String) {
        ui.waitForIdle()
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(requireNotNull(context.getExternalFilesDir("validation")),"ai-provenance").apply {mkdirs()}
        File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }

    @Test fun aiDisclosureOpensAReadableManifestWithSeparateIntegrityAndTrustCopy() {
        show(listOf(generation))
        ui.onNodeWithText("AI DETECTED").assertIsDisplayed()
        ui.onNodeWithText("Declared in metadata").assertIsDisplayed()
        ui.onNodeWithTag("AI provenance manifest").assertDoesNotExist()
        evidence("declared-ai-collapsed-dark")
        ui.onNodeWithTag("AI provenance button").performClick()
        ui.onNodeWithText("AI use, as declared").assertIsDisplayed()
        ui.onNodeWithText("Bluesky · Image 1").assertIsDisplayed()
        ui.onNodeWithText(generation.paragraphs.first()).assertIsDisplayed()
        ui.onNodeWithText(generation.verification!!).assertExists()
        ui.onNodeWithText("Recorded tools: Example Image Studio 4.2.").assertExists()
        evidence("declared-ai-expanded-dark")
        ui.onNodeWithTag("AI provenance button").performClick()
        ui.onNodeWithTag("AI provenance manifest").assertDoesNotExist()
    }

    @Test fun fusedSourcesKeepTheirOwnAttachmentAndEvidenceWithoutCallingConventionalEditsAI() {
        val second=AiDisclosureEntry(source="Instagram",asset="Image 2",paragraphs=listOf("The metadata records an algorithmic enhancement, such as sharpening or noise reduction. It does not declare generative AI use."),evidence="Embedded IPTC metadata",verification="This is an unsigned declaration.",aiDeclared=false)
        show(listOf(generation,second),dark=false)
        ui.onNodeWithTag("AI provenance button").performClick()
        ui.onNodeWithTag("AI provenance entry 0").assertExists()
        ui.onNodeWithTag("AI provenance entry 1").assertExists()
        ui.onNodeWithText("Bluesky · Image 1").assertIsDisplayed()
        ui.onNodeWithText("Instagram · Image 2").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(second.paragraphs.single()).performScrollTo().assertIsDisplayed()
        evidence("fused-source-disclosures-light")
    }

    @Test fun ordinaryRecordedHistoryUsesANeutralDisclosureAndMissingMetadataGetsNoVerdict() {
        val ordinary=AiDisclosureEntry(source="Mastodon",asset="Image 1",paragraphs=listOf("This image is declared as a camera capture. The recorded history includes a crop."),evidence="Embedded Content Credentials",verification="The signature has not been checked.",aiDeclared=false)
        show(listOf(ordinary))
        ui.onNodeWithText("AI DETECTED").assertDoesNotExist()
        ui.onNodeWithText("CONTENT HISTORY").assertIsDisplayed()
        ui.onNodeWithTag("AI provenance button").performClick()
        ui.onNodeWithText("Behind this media").assertIsDisplayed()
        ui.onNodeWithText(ordinary.paragraphs.single()).assertIsDisplayed()
        evidence("ordinary-history-neutral-dark")
    }

    @Test fun emptyDeclarationsDoNotProduceABadge() {
        show(listOf(AiDisclosureEntry("X","Image 1",emptyList(),"",aiDeclared=true)))
        ui.onNodeWithTag("AI provenance button").assertDoesNotExist()
        ui.onNodeWithTag("AI provenance manifest").assertDoesNotExist()
        ui.onNodeWithText("AI DETECTED").assertDoesNotExist()
    }

    @Test fun declaredMetadataRemovalHasItsOwnWarningWithoutAnAiVerdict() {
        val removal=AiDisclosureEntry(source="Mastodon",asset="Image 1",paragraphs=listOf("The supplied Content Credentials record declares the removal of camera details from its history. This redaction claim has not been confirmed against a valid file credential."),evidence="Content Credentials redaction record",verification="Content Credentials validation failed. The recorded declarations are unverified.",aiDeclared=false,metadataRemoved=true)
        show(listOf(removal))
        ui.onNodeWithText("METADATA REMOVED").assertIsDisplayed()
        ui.onNodeWithText("Declared removal").assertIsDisplayed()
        ui.onNodeWithText("AI DETECTED").assertDoesNotExist()
        ui.onNodeWithTag("AI provenance button").performClick()
        ui.onNodeWithText("Metadata removed, as declared").assertIsDisplayed()
        ui.onNodeWithText(removal.paragraphs.single()).assertIsDisplayed()
        evidence("declared-metadata-removal-dark")
    }

    @Test fun matchingCopyShowsAMetadataGapWithoutClaimingAiOrWhoRemovedIt() {
        val gap=AiDisclosureEntry(source="Instagram",asset="Image 1",paragraphs=listOf("Another copy of the same encoded picture contains XMP information that this copy does not. The comparison cannot tell whether information was removed here or added to the other copy."),evidence="Local comparison of matching encoded pictures",aiDeclared=false,metadataGap=true)
        show(listOf(gap),dark=false)
        ui.onNodeWithText("METADATA GAP").assertIsDisplayed()
        ui.onNodeWithText("Evidence of metadata difference").assertIsDisplayed()
        ui.onNodeWithText("AI DETECTED").assertDoesNotExist()
        ui.onNodeWithText("METADATA REMOVED").assertDoesNotExist()
        ui.onNodeWithTag("AI provenance button").performClick()
        ui.onNodeWithText("A gap in the record").assertIsDisplayed()
        ui.onNodeWithText(gap.paragraphs.single()).assertIsDisplayed()
        evidence("same-picture-metadata-gap-light")
    }

    @Test fun aiAndMetadataEvidenceShareOneClearlyLabelledDisclosure() {
        show(listOf(generation.copy(metadataGap=true)))
        ui.onNodeWithText("AI + METADATA").assertIsDisplayed()
        ui.onNodeWithText("AI use & metadata difference").assertIsDisplayed()
        ui.onNodeWithTag("AI provenance button").performClick()
        ui.onNodeWithText("AI & metadata history").assertIsDisplayed()
    }

    @Test fun checkedButUnlabeledMediaHasOnlyASmallNeutralInformationControl() {
        val unknown=AiDisclosureEntry("X","Image 1",listOf("anti social inspected this delivered media copy and found no readable AI-use declaration. AI use remains unknown."),"",aiDeclared=false,inspectionStatus=AiInspectionStatus.NO_DECLARATION)
        show(listOf(unknown))
        ui.onNodeWithContentDescription("AI status unknown").assertIsDisplayed()
        listOf("AI DETECTED","METADATA REMOVED","METADATA GAP","CONTENT HISTORY").forEach {ui.onNodeWithText(it).assertDoesNotExist()}
        evidence("unknown-ai-neutral-information-dark")
        ui.onNodeWithTag("AI provenance button").performClick()
        ui.onNodeWithText("AI status unknown").assertIsDisplayed()
        ui.onNodeWithText(unknown.paragraphs.single()).assertIsDisplayed()
        evidence("unknown-ai-readable-manifest-dark")
    }

    @Test fun incompleteInspectionStaysNeutralAndKeepsTheReasonReadable() {
        val incomplete=AiDisclosureEntry("Instagram","Image 1",listOf("anti social could not fully inspect this delivered media copy. AI use remains unknown.","The inspection reached its size limit, so some metadata may be unread."),"",aiDeclared=false,inspectionStatus=AiInspectionStatus.INCOMPLETE)
        show(listOf(incomplete),dark=false)
        ui.onNodeWithContentDescription("AI inspection incomplete").assertIsDisplayed()
        ui.onNodeWithText("AI DETECTED").assertDoesNotExist();ui.onNodeWithText("METADATA REMOVED").assertDoesNotExist()
        ui.onNodeWithTag("AI provenance button").performClick()
        ui.onNodeWithText("AI status unknown").assertIsDisplayed()
        ui.onNodeWithText(incomplete.paragraphs.last()).assertIsDisplayed()
        evidence("incomplete-ai-inspection-neutral-light")
    }
}
