package example.com.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.Layer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonOptions
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import example.com.myapplication.MainActivity.Properties.Expressions.point_count_has
import example.com.myapplication.MainActivity.Properties.Expressions.point_count_has_not
import example.com.myapplication.MainActivity.Properties.clusterPointsOptions
import example.com.myapplication.MainActivity.Properties.textAllowOverlap
import example.com.myapplication.MainActivity.Properties.textColorBlack
import example.com.myapplication.MainActivity.Properties.textIgnorePlacement
import example.com.myapplication.MainActivity.Properties.textSize12
import kotlinx.coroutines.*
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity() {

  object Properties {
    //@formatter:off
    val polygonStrokeColor =  PropertyFactory.fillOutlineColor(Color.BLACK)
    val iconAnchorBottom =    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
    val iconAllowOverlap =    PropertyFactory.iconAllowOverlap(true)
    val lineCap =             PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
    val lineJoin =            PropertyFactory.lineJoin(Property.LINE_JOIN_MITER)
    val strokeWidth =         PropertyFactory.lineWidth(8F)
    val textAllowOverlap =    PropertyFactory.textAllowOverlap(true)
    val textIgnorePlacement = PropertyFactory.textIgnorePlacement(true)
    val `text-field` =        PropertyFactory.textField(Expression.get("text"))
    val textSize12 =           PropertyFactory.textSize(12f)
    val textColorBlack =       PropertyFactory.textColor(Color.BLACK)
    val point_count_text =     PropertyFactory.textField(Expressions.point_count_text)

    val clusterPointsOptions = GeoJsonOptions().withCluster(true)
    val Feature.cluster: Boolean get() = getBooleanProperty("cluster") ?: false
    val Feature.cluster_id: Long get() = getNumberProperty("cluster_id").toLong()

    object Expressions {
      val point_count_has      = Expression.has("point_count")
      val point_count_has_not  = Expression.not(point_count_has)
      val point_count_text     = Expression.toString(Expression.get("point_count"))
    }
    //@formatter:on
  }

  private val MAPBOX_TOKEN = "pk.eyJ1IjoiZC1raHVzYWlub3YiLCJhIjoiY2p1MHY1ZW8wMDI4eTRlbW9vZDU2d3hnNCJ9.FlKeuVamDZdoxO8TcBaLcw"

  private val scope = CoroutineScope(Dispatchers.Main)
  override fun onDestroy() = super.onDestroy().also { scope.cancel() }


  private val icon = "place"
  private val iconBitmap: Bitmap by lazy(LazyThreadSafetyMode.NONE) {
    ContextCompat
      .getDrawable(this, R.drawable.place)!!
      .toBitmap()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    Mapbox.getInstance(this, MAPBOX_TOKEN)

    val root = FrameLayout(this).apply {
      layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }

    setContentView(root)

    scope.launch {
      val style = addMap(root)

      style.addImage(icon, iconBitmap)

      var previousObjects: Pair<GeoJsonSource, List<Layer>>
      do {

        previousObjects = addObjects(style)

        delay(100)

        val (source, layers) = previousObjects
        layers.onEach { layer -> style.removeLayer(layer) }
        style.removeSource(source)

        println("style.getLayers() = ${style.layers.size}, style.getSources() = ${style.sources.size}")
      } while (true)
    }
  }

  private suspend fun addMap(root: ViewGroup): Style {
    val mapView = MapView(this).apply {
      layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }

    root.addView(mapView)

    return mapView
      .getMapAsync()
      .setStyleAsync(Style.MAPBOX_STREETS)
  }

  private fun addObjects(style: Style): Pair<GeoJsonSource, List<Layer>> {

    val rnd = Random()

    val points = (1..5_000).map {
      Feature.fromGeometry(
        Point.fromLngLat(
          rnd.nextInt(10) + 50.0,
          rnd.nextInt(10) + 50.0
        )
      )
    }

    return addFeatures(
      style,
      points,
      ::SymbolLayer,
      clusterPointsOptions
    ) {
      withProperties(
        Properties.iconAllowOverlap,
        Properties.iconAnchorBottom,
        PropertyFactory.iconImage(icon)
      )

      addClusteringToPoints(
        style = style,
        sourceId = sourceId,
        layer = this
      )
    }
  }

  private fun <T : Layer> addFeatures(
    style: Style,
    features: List<Feature>,
    create: (String, String) -> T,
    options: GeoJsonOptions? = null,
    also: T.(String) -> List<Layer>
  ): Pair<GeoJsonSource, List<Layer>> {
    val code = UUID.randomUUID().toString()

    val layerId = "$code-${features.first().geometry()?.type()}"
    val sourceId = layerId

    val source = GeoJsonSource(sourceId, FeatureCollection.fromFeatures(features), options)
    val layer = create(layerId, sourceId)

    val layers = also(layer, layerId)

    style.addSource(source)
    style.addLayer(layer)

    return source to (layers + layer)
  }

  fun addClusteringToPoints(
    style: Style,
    sourceId: String,
    layer: SymbolLayer
  ): List<Layer> {

    layer.withFilter(point_count_has_not)

    val clusterIconLayerId = "$sourceId-cluster-icon"
    val clusterIconLayer = SymbolLayer(clusterIconLayerId, sourceId)
      .withFilter(point_count_has)
      .withProperties(
        Properties.iconAllowOverlap,
        Properties.iconAnchorBottom,
        PropertyFactory.iconImage(icon)
      )

    val clusterTextLayerId = "$sourceId-cluster-text"
    val clusterTextLayer = SymbolLayer(clusterTextLayerId, sourceId)
      .withFilter(point_count_has)
      .withProperties(
        Properties.point_count_text,
        textSize12,
        textColorBlack,
        textIgnorePlacement,
        textAllowOverlap,
        PropertyFactory.textOffset(arrayOf(0f, -(iconBitmap.height / (24 * ratio))))
      )

    style.addLayer(clusterIconLayer)
    style.addLayerAbove(clusterTextLayer, clusterIconLayerId)

    return listOf(clusterIconLayer, clusterTextLayer)
  }
}

suspend fun MapView.getMapAsync(): MapboxMap = suspendCoroutine { continuation ->
  getMapAsync(continuation::resume)
}

suspend fun MapboxMap.setStyleAsync(style: String): Style = suspendCoroutine { continuation ->
  setStyle(style, continuation::resume)
}

private val Context.ratio get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)