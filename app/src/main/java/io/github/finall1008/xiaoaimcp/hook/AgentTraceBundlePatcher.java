package io.github.finall1008.xiaoaimcp.hook;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates a private, content-addressed copy of the host Stream RN bundle and adds the
 * second-level ToolCallItem expansion. The original Xiaomi cache is never modified.
 */
final class AgentTraceBundlePatcher {
    private static final String PATCH_MARKER = "xiaoai-agent-trace-v10";
    private static final String BUNDLE_END_MARKER = "__reactNativeBundleEndSuccess__";

    private static final String HELPERS = """
            /* xiaoai-agent-trace-v10 */
            var __xiaoaiTraceJson=function(e){if(null==e)return"宿主未提供";if("string"==typeof e){try{return JSON.stringify(JSON.parse(e),null,2)}catch(t){return e}}try{var t=JSON.stringify(e,null,2);return void 0===t?String(e):t}catch(t){return String(e)}};
            var __xiaoaiTraceDetails=function(e){var t=e||{},n=[],i=__xiaoaiTraceJson(t.arguments),o=null!=t.result?__xiaoaiTraceJson(t.result):null!=t.error?__xiaoaiTraceJson(t.error):"";return n.push("工具: "+(t.tool||"宿主未提供")),n.push("调用 ID: "+(t.tool_call_id||t.call_id||"宿主未提供")),n.push("状态: "+(t.event||t.status||"宿主未提供")),n.push("输入:\\n"+i),null!=t.result?n.push("输出:\\n"+o):null!=t.error?n.push("错误:\\n"+o):n.push("输出:\\n宿主未提供"),null!=t.duration_ms&&n.push("耗时: "+t.duration_ms+" ms"),null!=t.metadata&&n.push("元数据:\\n"+__xiaoaiTraceJson(t.metadata)),null!=t.file_attachments&&n.push("文件附件:\\n"+__xiaoaiTraceJson(t.file_attachments)),n.join("\\n\\n")};
            """;

    private AgentTraceBundlePatcher() {
    }

    static String patchPath(Context context, String sourcePath) throws IOException {
        if (context == null || sourcePath == null || sourcePath.isBlank()) {
            return sourcePath;
        }
        byte[] sourceBytes;
        if (sourcePath.startsWith("assets://")) {
            String assetName = sourcePath.substring("assets://".length());
            try (InputStream input = context.getAssets().open(assetName)) {
                sourceBytes = read(input);
            }
        } else {
            File source = new File(sourcePath);
            if (!source.isFile()) {
                return sourcePath;
            }
            sourceBytes = read(source);
        }
        String sourceHash = sha256(sourceBytes);
        File outputDirectory = new File(
                context.getCodeCacheDir(), "xiaoai-agent-trace/" + sourceHash
        );
        File output = new File(outputDirectory, "stream.bundle");
        if (output.isFile()) {
            String cached = new String(read(output), StandardCharsets.UTF_8);
            if (cached.contains(PATCH_MARKER) && cached.contains(BUNDLE_END_MARKER)) {
                return output.getAbsolutePath();
            }
            // A partial file must not be used as a script.
            //noinspection ResultOfMethodCallIgnored
            output.delete();
        }

        String sourceText = new String(sourceBytes, StandardCharsets.UTF_8);
        String patched = patchSource(sourceText);
        if (patched == null) {
            return sourcePath;
        }
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Unable to create Agent Trace bundle cache directory");
        }
        File temporary = new File(outputDirectory, "stream.bundle.tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(patched.getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        if (!temporary.renameTo(output)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("Unable to atomically publish Agent Trace bundle");
        }
        return output.getAbsolutePath();
    }

    static String patchSource(String source) {
        if (source == null || source.isBlank() || source.contains(PATCH_MARKER)) {
            return null;
        }
        if (!source.contains(BUNDLE_END_MARKER)) {
            return null;
        }

        int moduleAnchor = -1;
        int moduleStart = -1;
        int moduleEnd = -1;
        String module = null;
        int searchFrom = 0;
        while (true) {
            int candidateAnchor = source.indexOf("ToolCallItem:", searchFrom);
            if (candidateAnchor < 0) {
                break;
            }
            int candidateStart = source.lastIndexOf("__d(", candidateAnchor);
            int candidateEnd = source.indexOf("__d(", candidateAnchor + 1);
            if (candidateEnd < 0) {
                candidateEnd = source.length();
            }
            if (candidateStart >= 0 && candidateEnd > candidateStart) {
                String candidate = source.substring(candidateStart, candidateEnd);
                if (candidate.contains("normalizeStatusWithCancel")
                        && candidate.contains("parseArguments")
                        && candidate.contains("renderToolMessage")
                        && candidate.contains("numberOfLines:1")) {
                    if (module != null) {
                        return null;
                    }
                    moduleAnchor = candidateAnchor;
                    moduleStart = candidateStart;
                    moduleEnd = candidateEnd;
                    module = candidate;
                }
            }
            searchFrom = candidateAnchor + "ToolCallItem:".length();
        }
        if (module == null || moduleAnchor < 0 || moduleEnd <= moduleStart) {
            return null;
        }

        String withState = injectExpansionState(module);
        if (withState == null) {
            return null;
        }
        int fallbackStart = withState.indexOf("):(console.warn");
        int returnStart = withState.lastIndexOf("return", fallbackStart);
        int question = returnStart < 0
                ? -1 : withState.indexOf('?', returnStart + "return".length());
        int fallbackEnd = fallbackStart < 0
                ? -1 : withState.indexOf(",null)", fallbackStart);
        if (returnStart < 0 || question < 0 || question > fallbackStart
                || fallbackStart < 0 || fallbackEnd <= fallbackStart) {
            return null;
        }
        String guard = withState.substring(returnStart + "return".length(), question);
        // The leading ')' in the fallback marker closes the original true-branch
        // component call; keep it in the branch before wrapping it.
        String trueBranch = withState.substring(question + 1, fallbackStart + 1);
        String fallback = withState.substring(
                fallbackStart + 2, fallbackEnd + ",null)".length());
        if (guard.isBlank() || trueBranch.isBlank() || fallback.isBlank()) {
            return null;
        }

        String jsxNamespace = findNamespace(trueBranch, "jsxs", "p");
        String rnNamespace = findNamespace(trueBranch, "View", "n");
        String textStyle = findTextStyleVariable(module);
        String detailsVariable = findDetailsVariable(module);
        String detailsExpression = detailsVariable == null
                ? "(typeof K!==\"undefined\"?K:(typeof H!==\"undefined\"?H:null))"
                : detailsVariable;
        String expanded = "(0," + jsxNamespace + ".jsx)(" + rnNamespace
                + ".View,{style:{paddingTop:8,paddingHorizontal:4},children:(0,"
                + jsxNamespace + ".jsx)(" + rnNamespace
                + ".Text,{selectable:!0,style:" + textStyle
                + ",children:__xiaoaiTraceDetails(" + detailsExpression + ")})})";
        String toggle = "(0," + jsxNamespace + ".jsx)(" + rnNamespace
                + ".Pressable,{accessibilityRole:\"button\","
                + "accessibilityLabel:__xiaoaiTraceExpanded?\"收起工具详情\":\"展开工具详情\","
                + "hitSlop:8,onStartShouldSetResponderCapture:function(){return!0},"
                + "onPressIn:function(e){e&&e.stopPropagation&&e.stopPropagation()},"
                + "onPress:function(e){e&&e.stopPropagation&&e.stopPropagation();"
                + "__xiaoaiTraceSetExpanded(function(e){return!e})},"
                + "style:{paddingHorizontal:8,paddingVertical:4,marginLeft:8},children:(0,"
                + jsxNamespace + ".jsx)(" + rnNamespace + ".Text,{style:["
                + textStyle + ",{fontSize:12}],children:__xiaoaiTraceExpanded?"
                + "\"收起详情 ▲\":\"查看详情 ▼\"})})";
        String summary = "(0," + jsxNamespace + ".jsxs)(" + rnNamespace
                + ".View,{style:{flexDirection:\"row\",alignItems:\"center\",width:\"100%\"},"
                + "children:[(0," + jsxNamespace + ".jsx)(" + rnNamespace
                + ".View,{style:{flex:1},children:" + trueBranch + "})," + toggle + "]})";
        String replacement = guard + "?(0," + jsxNamespace + ".jsxs)(" + rnNamespace
                + ".View,{style:{flexDirection:\"column\",width:\"100%\"},children:["
                + summary + ",__xiaoaiTraceExpanded?" + expanded + ":null]}):" + fallback;
        String patchedModule = withState.substring(0, returnStart)
                + "return" + replacement
                + withState.substring(fallbackEnd + ",null)".length());
        String result = source.substring(0, moduleStart)
                + HELPERS
                + patchedModule
                + source.substring(moduleEnd);
        result = patchReasoningMarkdown(result);
        return result.contains(PATCH_MARKER) && result.contains("__xiaoaiTraceDetails(")
                ? result : null;
    }

    /**
     * The host deliberately puts reasoning markdown in a zero-size/transparent wrapper and
     * its section component only creates an empty placeholder. Make the public reasoning
     * section visible while retaining the host's existing thinking-chain renderer and state.
     */
    private static String patchReasoningMarkdown(String source) {
        String visible = patchUniqueModule(
                source,
                "hiddenMarkdown:{width:0,height:0,opacity:0,overflow:'hidden'}",
                module -> module.replace(
                        "hiddenMarkdown:{width:0,height:0,opacity:0,overflow:'hidden'}",
                        "hiddenMarkdown:{width:'100%',opacity:1,overflow:'visible'}"
                ),
                "groupSections"
        );
        return patchUniqueModule(
                visible,
                "var r=e.StyleSheet.create({wrap:{flex:1}})",
                module -> {
                    String oldReturn =
                            "return!0===(null==o?void 0:o.showCancel)||S?"
                                    + "(0,t.jsx)(e.View,{style:r.wrap}):null";
                    if (!module.contains(oldReturn)
                            || !module.contains("i.RNInlineMarkdownView")
                            || !module.contains("getRendererProps")) {
                        return module;
                    }
                    String newReturn =
                            "return!0===(null==o?void 0:o.showCancel)||S?"
                                    + "(0,t.jsx)(e.View,{style:r.wrap,children:(0,t.jsx)(s,Object.assign({},M,{"
                                    + "markdownText:S,textStyle:h.textStyle||{},numberOfLines:999999,"
                                    + "containerStyle:r.wrap}))}):null";
                    return module.replace(oldReturn, newReturn);
                },
                "i.RNInlineMarkdownView",
                "getRendererProps"
        );
    }

    private interface ModuleTransformer {
        String transform(String module);
    }

    private static String patchUniqueModule(
            String source,
            String anchor,
            ModuleTransformer transformer,
            String... requiredMarkers
    ) {
        int anchorPosition = source.indexOf(anchor);
        if (anchorPosition < 0 || source.indexOf(anchor, anchorPosition + anchor.length()) >= 0) {
            return source;
        }
        int moduleStart = source.lastIndexOf("__d(", anchorPosition);
        int moduleEnd = source.indexOf("__d(", anchorPosition + anchor.length());
        if (moduleEnd < 0) {
            moduleEnd = source.length();
        }
        if (moduleStart < 0 || moduleEnd <= moduleStart) {
            return source;
        }
        String module = source.substring(moduleStart, moduleEnd);
        for (String marker : requiredMarkers) {
            if (!module.contains(marker)) {
                return source;
            }
        }
        String transformed = transformer.transform(module);
        return transformed == null || transformed.equals(module)
                ? source
                : source.substring(0, moduleStart) + transformed + source.substring(moduleEnd);
    }

    private static String injectExpansionState(String module) {
        String reactNamespace = findReactNamespace(module);
        String state = "__xiaoaiTraceState=(0," + reactNamespace + ".useState)(!1),"
                + "__xiaoaiTraceExpanded=__xiaoaiTraceState[0],"
                + "__xiaoaiTraceSetExpanded=__xiaoaiTraceState[1],";
        int componentStart = module.indexOf("_e.default=function(e){");
        if (componentStart >= 0) {
            int insertionPoint = componentStart + "_e.default=function(e){".length();
            int declarationStart = module.indexOf("var ", insertionPoint);
            if (declarationStart < 0) {
                return null;
            }
            return module.substring(0, declarationStart) + "var " + state
                    + module.substring(declarationStart + "var ".length());
        }
        String legacyAnchor = "var M,_,j,w,x,b=e.cardData,S=e.RNInlineMarkdownView";
        int legacyStart = module.indexOf(legacyAnchor);
        if (legacyStart >= 0) {
            return module.substring(0, legacyStart) + "var " + state
                    + module.substring(legacyStart + "var ".length());
        }
        return null;
    }

    private static String findReactNamespace(String module) {
        Matcher matcher = Pattern.compile(
                "\\(0,([A-Za-z_$][A-Za-z0-9_$]*)\\.use(?:Context|Memo|Ref|Effect)\\)"
        ).matcher(module);
        return matcher.find() ? matcher.group(1) : "t";
    }

    private static String findNamespace(String source, String member, String fallback) {
        Matcher matcher = Pattern.compile(
                "\\(0,([A-Za-z_$][A-Za-z0-9_$]*)\\." + Pattern.quote(member) + "\\)"
        ).matcher(source);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static String findTextStyleVariable(String source) {
        Matcher matcher = Pattern.compile(
                "(?:var|,)([A-Za-z_$][A-Za-z0-9_$]*)=\\(0,[A-Za-z_$][A-Za-z0-9_$]*\\.useMemo\\)"
                        + "\\(function\\(\\)\\{return\\{fontSize:14"
        ).matcher(source);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return source.contains("Y=") ? "Y" : "te";
    }

    private static String findDetailsVariable(String source) {
        Matcher matcher = Pattern.compile(
                "(?:var|,)([A-Za-z_$][A-Za-z0-9_$]*)=\\(0,[A-Za-z_$][A-Za-z0-9_$]*\\.useMemo\\)"
                        + "\\(function\\(\\)\\{var [^;]*Object\\.assign\\(\\{\\},[^;]*\\{event:"
        ).matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static byte[] read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return read(input);
        }
    }

    private static byte[] read(InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(bytes);
            StringBuilder value = new StringBuilder(result.length * 2);
            for (byte item : result) {
                value.append(String.format("%02x", item & 0xff));
            }
            return value.toString();
        } catch (Exception error) {
            throw new IOException("Unable to hash RN bundle", error);
        }
    }
}
