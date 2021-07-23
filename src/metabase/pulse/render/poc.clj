(ns metabase.pulse.render.poc
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.awt.RenderingHints
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           java.nio.charset.StandardCharsets
           [org.apache.batik.anim.dom SAXSVGDocumentFactory SVGOMDocument]
           org.apache.batik.gvt.renderer.ImageRenderer
           [org.apache.batik.transcoder TranscoderInput TranscoderOutput]
           org.apache.batik.transcoder.image.PNGTranscoder
           (org.graalvm.polyglot Context HostAccess Source Value)))

(def ^:private bundle-path
  ;; todo: this will move to app/dist when the bundle is in the tree
  "resources/frontend_client/app/dist/lib-static-viz.bundle.js")

(def ^:private src-api
  "API for calling to the javascript bundle. Entry points are the functions
  - timeseries_line
  - timeseries_bar
  - categorical_donut
  "
  "

const toJSArray = (a) => {
  var jsArray = [];
  for (var i = 0; i < a.length; i++) {
    jsArray[i] = a[i];
  }
  return jsArray;
}

const date_accessors = {
  x: (row) => new Date(row[0]).valueOf(),
  y: (row) => row[1],
}

const dimension_accessors = {
  dimension: (row) => row[0],
  metric: (row) => row[1],
}

function timeseries_line (data) {
  return StaticViz.RenderChart(\"timeseries/line\", {
    data: toJSArray(data),
    accessors: date_accessors
 })
}

function timeseries_bar (data) {
  return StaticViz.RenderChart(\"timeseries/bar\", {
    data: toJSArray(data),
    accessors: date_accessors
 })
}

function categorical_donut (data) {
  return StaticViz.RenderChart(\"categorical/donut\", {
    data: toJSArray(data),
    accessors: dimension_accessors
 })
}
")

(def ^:private ^Context context
  "Javascript context suitable for evaluating the charts. It has the chart bundle and the above `src-api` in its
  environment suitable for creating charts."
  ;; todo is this thread safe? Should we have a resource pool on top of this? Or create them fresh for each invocation
  (delay
    (let [^Context context (.. (Context/newBuilder (into-array String ["js"]))
                      (allowHostAccess HostAccess/ALL)
                      (allowHostClassLookup (reify java.util.function.Predicate
                                              (test [_ _] true)))
                      (out System/out)
                      (err System/err)
                      (allowIO true)
                      (build))]
      (doto context
        (.eval (.build (Source/newBuilder "js" (io/file bundle-path))))
        (.eval (.build (Source/newBuilder "js" ^String src-api "src call")))))))


(defn- parse-svg-string [^String s]
  (let [factory (SAXSVGDocumentFactory. "org.apache.xerces.parsers.SAXParser")]
    (with-open [is (ByteArrayInputStream. (.getBytes s StandardCharsets/UTF_8))]
      (.createDocument factory "file:///fake.svg" is))))

(defn- high-quality-png-transcoder ^PNGTranscoder []
  (proxy [PNGTranscoder] []
    (createRenderer []
      (let [add-hint                (fn [^RenderingHints hints k v] (.add hints (RenderingHints. k v)))
            ^ImageRenderer renderer (proxy-super createRenderer)
            hints                   (RenderingHints.
                                     RenderingHints/KEY_ALPHA_INTERPOLATION
                                     RenderingHints/VALUE_ALPHA_INTERPOLATION_QUALITY)]
        (doto hints
          (add-hint RenderingHints/KEY_ALPHA_INTERPOLATION RenderingHints/VALUE_ALPHA_INTERPOLATION_QUALITY)
          (add-hint RenderingHints/KEY_INTERPOLATION       RenderingHints/VALUE_INTERPOLATION_BICUBIC)
          (add-hint RenderingHints/KEY_ANTIALIASING        RenderingHints/VALUE_ANTIALIAS_ON)
          (add-hint RenderingHints/KEY_COLOR_RENDERING     RenderingHints/VALUE_COLOR_RENDER_QUALITY)
          (add-hint RenderingHints/KEY_DITHERING           RenderingHints/VALUE_DITHER_DISABLE)
          (add-hint RenderingHints/KEY_RENDERING           RenderingHints/VALUE_RENDER_QUALITY)
          (add-hint RenderingHints/KEY_STROKE_CONTROL      RenderingHints/VALUE_STROKE_PURE)
          (add-hint RenderingHints/KEY_FRACTIONALMETRICS   RenderingHints/VALUE_FRACTIONALMETRICS_ON)
          (add-hint RenderingHints/KEY_TEXT_ANTIALIASING   RenderingHints/VALUE_TEXT_ANTIALIAS_OFF))
        (.setRenderingHints renderer hints)
        renderer))))

(defn- render-svg
  ^bytes [^SVGOMDocument svg-document]
  (with-open [os (ByteArrayOutputStream.)]
    (let [in         (TranscoderInput. svg-document)
          out        (TranscoderOutput. os)
          transcoder (high-quality-png-transcoder)]
      (.addTranscodingHint transcoder PNGTranscoder/KEY_WIDTH (float 2000))
      (.transcode transcoder in out))
    (.toByteArray os)))

(defn- svg-string->bytes [s]
  (let [s (-> s
              (str/replace  #"<svg " "<svg xmlns=\"http://www.w3.org/2000/svg\" ")
              (str/replace #"fill=\"transparent\"" "fill-opacity=\"0.0\""))]
    (-> s parse-svg-string render-svg)))

(defn- execute-fn
  [^Context context fn & args]
  (let [fn-ref (.eval context "js" fn)
        args   (into-array Object args)]
    (assert (.canExecute fn-ref) (str "cannot execute " fn))
    (.execute fn-ref args)))

(defn timelineseries-line
  "Clojure entrypoint to render a timeseries line char. Rows should be tuples of [datetime numeric-value]. Returns a
  byte array of a png file."
  [rows]
  (let [svg-string (.asString ^Value (execute-fn @context "timeseries_line" rows))]
    (svg-string->bytes svg-string)))

(defn timelineseries-bar
  "Clojure entrypoint to render a timeseries bar char. Rows should be tuples of [datetime numeric-value]. Returns a byte
  array of a png file"
  [rows]
  (let [svg-string (.asString ^Value (execute-fn @context "timeseries_bar" rows))]
    (svg-string->bytes svg-string)))

(defn categorical-donut
  "Clojure entrypoint to render a categorical donut chart. Rows should be tuples of [category numeric-value]. Returns a
  byte array of a png file"
  [rows]
  (let [svg-string (.asString ^Value (execute-fn @context "categorical_donut" rows))]
    (svg-string->bytes svg-string)))
