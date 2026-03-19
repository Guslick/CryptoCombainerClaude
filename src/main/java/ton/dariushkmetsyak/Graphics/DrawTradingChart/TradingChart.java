package ton.dariushkmetsyak.Graphics.DrawTradingChart;

import ExecPack.App;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYShapeAnnotation;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYDotRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.TimeUnit;


public class TradingChart {
    // ── Per-thread chart state via ThreadLocal ────────────────────────────────
    // Each trading thread gets an isolated chart instance so multiple users
    // can trade simultaneously without charts interfering with each other.
    private static final ThreadLocal<TradingChart> THREAD_CHART = ThreadLocal.withInitial(TradingChart::new);

    public static TradingChart getForCurrentThread() {
        return THREAD_CHART.get();
    }

    /** Call in the trading thread's finally block to release resources. */
    public static void releaseForCurrentThread() {
        THREAD_CHART.remove();
    }

    // ── Per-instance fields ───────────────────────────────────────────────────
    TimeSeries series;
    TimeSeries markedPoints;
    IntervalMarker intervalMarker;
    XYPlot plot;
    JFreeChart chart;
    XYDotRenderer dotRenderer;

    public TradingChart() {
        series = new TimeSeries("");
        markedPoints = new TimeSeries("");
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(series);
        dataset.addSeries(markedPoints);
        chart = ChartFactory.createTimeSeriesChart(
                "PriceChart",
                "Time",
                "Price",
                dataset,
                true,
                true,
                true);
        plot = chart.getXYPlot();


        // Рендерер для выделенных точек (без линий)
        dotRenderer = new XYDotRenderer();
        dotRenderer.setDotWidth(1);  // Размер точек
        dotRenderer.setDotHeight(1);
        dotRenderer.setSeriesPaint(1, Color.RED);  // Красный цвет для выделенных точек
        plot.setRenderer(1, dotRenderer);  // Применяем рендерер для второй серии (только точки)

        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer();
        lineRenderer.setSeriesLinesVisible(1, false);
        lineRenderer.setSeriesShapesVisible(0, false);
        lineRenderer.setSeriesPaint(0, Color.BLACK);
        plot.setRenderer(0, lineRenderer);
    }



    public static void addSimplePoint(double timestamp, double price) {
        getForCurrentThread().addSimplePointI(timestamp, price);
    }
    public void addSimplePointI(double timestamp, double price) {
        series.addOrUpdate(new Millisecond(Date.from(Instant.ofEpochMilli((long)timestamp))), price);
    }
    public static void addBuyIntervalMarker(double timestamp, double price) {
        getForCurrentThread().addBuyIntervalMarkerI(timestamp, price);
    }
    public void addBuyIntervalMarkerI(double timestamp, double price) {
        addSimplePointI(timestamp, price);
        intervalMarker = new IntervalMarker(timestamp, timestamp);
        intervalMarker.setPaint(Color.decode("#F0E68C"));
        ValueMarker valueMarker = new ValueMarker(timestamp);
        valueMarker.setPaint(Color.decode("#1565C0")); // Blue for BUY
        XYTextAnnotation annotation = new XYTextAnnotation("BUY " + String.valueOf(price), timestamp, price);
        annotation.setPaint(Color.decode("#1565C0"));
        annotation.setFont(new Font("Verdana", Font.BOLD, 20));
        plot.addAnnotation(annotation);
        valueMarker.setStroke(new BasicStroke(5));
        plot.addDomainMarker(valueMarker);
        plot.addDomainMarker(intervalMarker);
    }
    public static void addSimplePriceMarker(double timestamp, double price) {
        getForCurrentThread().addSimplePriceMarkerI(timestamp, price);
    }
    void addSimplePriceMarkerI(double timestamp, double price) {            //добавляем на график
//        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
//      //  renderer.setSeriesLinesVisible(0, true);  // Показывать линии
//        renderer.setSeriesShapesVisible(0, true); // Показывать точки
//        // Устанавливаем красные точки на графике (выделяем красным)
//        renderer.setSeriesShape(0, new java.awt.geom.Ellipse2D.Double(0, 0, 2, 2)); // Устанавливаем форму маркера (круг)
//        renderer.setSeriesPaint(0, Color.BLACK);   // Цвет точек
      //  plot.setRenderer(renderer);

        markedPoints.addOrUpdate(new Millisecond(Date.from(Instant.ofEpochMilli((long)timestamp))), price);
        XYTextAnnotation annotation = new XYTextAnnotation("", timestamp, price);
        annotation.setPaint(Color.RED);
        annotation.setFont(new Font("Verdana", Font.BOLD, 15));
        annotation.setTextAnchor(TextAnchor.TOP_CENTER);
        ValueMarker valueMarker = new ValueMarker(timestamp);
        valueMarker.setPaint(Color.BLACK);
        valueMarker.setStroke(new BasicStroke(0));
    }
    public static void addSellIntervalMarker(double timestamp, double price) {
        getForCurrentThread().addSellIntervalMarkerI(timestamp, price, false);
    }
    public static void addSellProfitMarker(double timestamp, double price) {
        getForCurrentThread().addSellIntervalMarkerI(timestamp, price, true);
    }
    public void addSellIntervalMarkerI(double timestamp, double price, boolean isProfit) {
        addSimplePointI(timestamp, price);
        if (intervalMarker == null) {
            intervalMarker = new IntervalMarker(timestamp, timestamp);
            intervalMarker.setPaint(Color.decode("#F0E68C"));
            plot.addDomainMarker(intervalMarker);
        }
        intervalMarker.setEndValue(timestamp);
        Color sellColor = isProfit ? Color.decode("#00C853") : Color.RED; // Green for profit, Red for loss
        String label = isProfit ? "SELL+" : "SELL-";
        XYTextAnnotation annotation = new XYTextAnnotation(label + " " + String.valueOf(price), timestamp, price);
        annotation.setPaint(sellColor);
        annotation.setFont(new Font("Verdana", Font.BOLD, 20));
        plot.addAnnotation(annotation);
        ValueMarker valueMarker = new ValueMarker(timestamp);
        valueMarker.setPaint(sellColor);
        valueMarker.setStroke(new BasicStroke(5));
        plot.addDomainMarker(valueMarker);
        intervalMarker = null;
    }

    public static void extendInBuyArea(double timestamp) {
        getForCurrentThread().extendInBuyAreaI(timestamp);
    }
    void extendInBuyAreaI(double timestamp) {
        if (intervalMarker != null) intervalMarker.setEndValue(timestamp);
    }

    public static void makeScreenShot(String path) {
        getForCurrentThread().makeScreenShotI(path);
    }
    public void makeScreenShotI(String path) {
        File file = new File(path);
        try {
            ChartUtils.saveChartAsPNG(file, chart, 1920, 1080);
        } catch (IOException e) {
            System.err.println("Не получилось сохранить скриншот");
            e.printStackTrace();
        }
    }
    public static void clearChart() {
        getForCurrentThread().clearChartI();
    }
    void clearChartI() {
        // Re-initialize this instance's chart (same as constructor)
        series = new TimeSeries("");
        markedPoints = new TimeSeries("");
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(series);
        dataset.addSeries(markedPoints);
        chart = ChartFactory.createTimeSeriesChart("PriceChart", "Time", "Price", dataset, true, true, true);
        plot = chart.getXYPlot();
        dotRenderer = new XYDotRenderer();
        dotRenderer.setDotWidth(1);
        dotRenderer.setDotHeight(1);
        dotRenderer.setSeriesPaint(1, Color.RED);
        plot.setRenderer(1, dotRenderer);
        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer();
        lineRenderer.setSeriesLinesVisible(1, false);
        lineRenderer.setSeriesShapesVisible(0, false);
        lineRenderer.setSeriesPaint(0, Color.BLACK);
        plot.setRenderer(0, lineRenderer);
        intervalMarker = null;
    }

    public static void drawChart(Chart chartData) {
        getForCurrentThread().chart.setTitle(chartData.getCoinName());
        for (double[] p : chartData.getPrices())
            TradingChart.addSimplePoint(p[0], p[1]);
    }
    public static void drawChart(ArrayList<double[]> chartData, String chartTitle) {
        getForCurrentThread().chart.setTitle(chartTitle);
        for (double[] p : chartData)
            TradingChart.addSimplePriceMarker(p[0], p[1]);
    }
}
