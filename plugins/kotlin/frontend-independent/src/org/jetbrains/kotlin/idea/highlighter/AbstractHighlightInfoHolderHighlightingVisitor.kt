package org.jetbrains.kotlin.idea.highlighter

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange

abstract class AbstractHighlightInfoHolderHighlightingVisitor protected constructor(private val holder: HighlightInfoHolder) :
    AbstractHighlightingVisitor() {
    override fun createInfoAnnotation(textRange: TextRange, message: String?, textAttributes: TextAttributesKey?) {
        HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
            .range(textRange)
            .also { builder -> message?.let { builder.description(it) } }
            .also { builder ->
                textAttributes?.let {
                    builder.textAttributes(it)
                }
            }
            .create()
            .also { holder.add(it) }
    }
}