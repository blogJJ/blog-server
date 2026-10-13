package com.blog.board.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * Claude가 그린 SVG에서 그림 요소만 남긴다 (T155, BLG-15, D-126). SVG는 글자로 된 문서라 스크립트·외부 주소·HTML을 숨길 수 있어 받은 그대로
 * 저장하지 않는다.
 *
 * <ul>
 *   <li>XML로 읽지 못하거나 DOCTYPE(외부 엔티티, XXE)이 있으면 통째로 버린다.
 *   <li>허용 목록에 있는 요소와 표현 속성만 남긴다. script, foreignObject, style 요소, 글자, on* 속성은 모두 빠진다.
 *   <li>href는 use와 그라데이션에서 같은 문서 안(#id)만 가리킬 수 있다. url(...)도 #id만.
 *   <li>use가 다시 use를 품은 요소를 가리키면 뺀다. 겹겹이 복제해 브라우저를 멈추게 하는 그림을 막는다.
 *   <li>받은 것과 남긴 것 모두 200KB를 넘으면 버린다.
 * </ul>
 *
 * <p>남긴 결과도 화면에는 {@code <img>}로만 보여 준다. 문서에 직접 넣지 않는다.
 */
public final class SvgSanitizer {

  public static final int MAX_BYTES = 200 * 1024;

  private static final String SVG_NS = "http://www.w3.org/2000/svg";
  private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
  private static final int MAX_DEPTH = 32;
  private static final int MAX_ELEMENTS = 5000;

  private static final Set<String> ELEMENTS =
      Set.of(
          "svg",
          "g",
          "path",
          "rect",
          "circle",
          "ellipse",
          "line",
          "polyline",
          "polygon",
          "defs",
          "linearGradient",
          "radialGradient",
          "stop",
          "clipPath",
          "mask",
          "use");

  // 모양·색·위치만. class·style 요소가 없으니 class는 쓸모가 없어 뺀다
  private static final Set<String> ATTRIBUTES =
      Set.of(
          "id",
          "x",
          "y",
          "x1",
          "y1",
          "x2",
          "y2",
          "cx",
          "cy",
          "r",
          "rx",
          "ry",
          "fx",
          "fy",
          "fr",
          "width",
          "height",
          "d",
          "points",
          "pathLength",
          "transform",
          "fill",
          "fill-opacity",
          "fill-rule",
          "stroke",
          "stroke-width",
          "stroke-opacity",
          "stroke-linecap",
          "stroke-linejoin",
          "stroke-dasharray",
          "stroke-dashoffset",
          "stroke-miterlimit",
          "opacity",
          "visibility",
          "display",
          "clip-path",
          "clip-rule",
          "clipPathUnits",
          "mask",
          "maskUnits",
          "maskContentUnits",
          "offset",
          "stop-color",
          "stop-opacity",
          "gradientUnits",
          "gradientTransform",
          "spreadMethod",
          "shape-rendering",
          "vector-effect",
          "paint-order",
          "style");

  private static final Set<String> HREF_ELEMENTS =
      Set.of("use", "linearGradient", "radialGradient");
  private static final Pattern ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]{0,63}");
  private static final Pattern LOCAL_HREF = Pattern.compile("#([A-Za-z_][A-Za-z0-9_.:-]{0,63})");
  private static final Pattern URL_FUNC = Pattern.compile("(?i)url\\s*\\(");
  private static final Pattern LOCAL_URL =
      Pattern.compile("(?i)url\\(\\s*(['\"]?)#[a-z_][a-z0-9_.:-]{0,63}\\1\\s*\\)");
  // 속성 값에 이런 글자가 있으면 그 속성만 뺀다. 표현 속성에는 필요 없는 것들이다
  private static final Pattern FORBIDDEN_VALUE =
      Pattern.compile("(?i)javascript:|data:|expression|@import|[<>\\\\]|&#");
  private static final String INVALID_VIEW_BOX = "Invalid viewBox";
  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

  private SvgSanitizer() {}

  /**
   * SVG 문서를 정리해 돌려준다.
   *
   * @throws IllegalArgumentException 읽지 못했거나, 크기를 넘거나, 루트가 svg가 아니거나, 남은 그림이 없을 때
   */
  public static String sanitize(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("Empty SVG");
    }
    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("SVG too large");
    }
    Element root = parse(bytes).getDocumentElement();
    if (!"svg".equals(root.getLocalName()) || !inSvgNamespace(root)) {
      throw new IllegalArgumentException("Root is not svg");
    }
    String[] viewBox = viewBox(root);

    Counter counter = new Counter();
    List<Item> children = cleanChildren(root, 1, counter);
    dropRecursiveUses(children);
    if (children.isEmpty()) {
      throw new IllegalArgumentException("Nothing left to draw");
    }

    StringBuilder out = new StringBuilder(bytes.length);
    out.append("<svg xmlns=\"").append(SVG_NS).append('"');
    out.append(" viewBox=\"").append(String.join(" ", viewBox)).append('"');
    out.append(" width=\"").append(viewBox[2]).append('"');
    out.append(" height=\"").append(viewBox[3]).append('"');
    // 머리 영역은 폭에 맞춰 위아래를 자르므로 가운데를 기준으로 채운다
    out.append(" preserveAspectRatio=\"xMidYMid slice\">");
    for (Item child : children) {
      child.write(out);
    }
    out.append("</svg>");
    String result = out.toString();
    if (result.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IllegalArgumentException("SVG too large");
    }
    return result;
  }

  private static Document parse(byte[] bytes) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      // DOCTYPE이 있으면 읽지 않는다. 외부 엔티티(XXE)와 엔티티 폭탄을 한 번에 막는다
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      DocumentBuilder builder = f.newDocumentBuilder();
      builder.setErrorHandler(null); // 오류를 표준 오류로 찍지 않고 예외로만 받는다
      return builder.parse(new ByteArrayInputStream(bytes));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid SVG", e);
    }
  }

  /** 루트의 viewBox. 없으면 width·height로 만든다. 숫자 네 개, 폭·높이는 0보다 커야 한다. */
  private static String[] viewBox(Element root) {
    String value = root.getAttributeNS(null, "viewBox").strip();
    if (value.isEmpty()) {
      value =
          "0 0 "
              + root.getAttributeNS(null, "width").strip()
              + " "
              + root.getAttributeNS(null, "height").strip();
    }
    String[] parts = value.split("[\\s,]+");
    if (parts.length != 4) {
      throw new IllegalArgumentException(INVALID_VIEW_BOX);
    }
    for (String p : parts) {
      if (!NUMBER.matcher(p).matches()) {
        throw new IllegalArgumentException(INVALID_VIEW_BOX);
      }
    }
    if (Double.parseDouble(parts[2]) <= 0 || Double.parseDouble(parts[3]) <= 0) {
      throw new IllegalArgumentException(INVALID_VIEW_BOX);
    }
    return parts;
  }

  private static List<Item> cleanChildren(Element parent, int depth, Counter counter) {
    List<Item> items = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      // 글자·주석·CDATA·처리 지시는 모두 버린다. 그림에 글자를 넣지 않는다
      if (nodes.item(i) instanceof Element e) {
        Item item = clean(e, depth, counter);
        if (item != null) {
          items.add(item);
        }
      }
    }
    return items;
  }

  /** 허용하지 않는 요소는 안쪽까지 통째로 뺀다 (script, foreignObject, style, text, image ...). */
  private static Item clean(Element e, int depth, Counter counter) {
    String name = e.getLocalName();
    if (!inSvgNamespace(e) || !ELEMENTS.contains(name) || "svg".equals(name)) {
      return null;
    }
    if (depth > MAX_DEPTH || ++counter.elements > MAX_ELEMENTS) {
      throw new IllegalArgumentException("SVG too complex");
    }
    Map<String, String> attrs = cleanAttributes(e, name);
    if ("use".equals(name) && !attrs.containsKey("href")) {
      return null; // 바깥을 가리키던 use
    }
    return new Item(name, attrs, cleanChildren(e, depth + 1, counter));
  }

  /** 남길 속성. on*, 다른 네임스페이스, 모르는 속성은 뺀다. href는 같은 문서 안(#id)을 가리킬 때만 */
  private static Map<String, String> cleanAttributes(Element e, String name) {
    Map<String, String> attrs = new LinkedHashMap<>();
    NamedNodeMap list = e.getAttributes();
    for (int i = 0; i < list.getLength(); i++) {
      Attr a = (Attr) list.item(i);
      String ns = a.getNamespaceURI();
      String local = a.getLocalName() == null ? a.getName() : a.getLocalName();
      String value = a.getValue().strip();
      if (XLINK_NS.equals(ns) && "href".equals(local) || ns == null && "href".equals(local)) {
        if (HREF_ELEMENTS.contains(name) && LOCAL_HREF.matcher(value).matches()) {
          attrs.put("href", value); // xlink:href도 SVG2 href로 바꿔 쓴다
        }
      } else if (ns == null && ATTRIBUTES.contains(local) && safeValue(local, value)) {
        attrs.put(local, value);
      }
    }
    return attrs;
  }

  private static boolean safeValue(String attr, String value) {
    if (value.length() > 100_000 || FORBIDDEN_VALUE.matcher(value).find()) {
      return false;
    }
    if ("id".equals(attr)) {
      return ID.matcher(value).matches();
    }
    if ("style".equals(attr) && value.toLowerCase(Locale.ROOT).contains("behavior")) {
      return false;
    }
    // url(...)은 같은 문서 안(#id)만. 하나라도 바깥을 가리키면 속성을 뺀다
    int urls = 0;
    Matcher any = URL_FUNC.matcher(value);
    while (any.find()) {
      urls++;
    }
    if (urls == 0) {
      return true;
    }
    int local = 0;
    Matcher ok = LOCAL_URL.matcher(value);
    while (ok.find()) {
      local++;
    }
    return local == urls;
  }

  /** use가 가리키는 요소 안에 다시 use가 있으면 그 use를 뺀다. */
  private static void dropRecursiveUses(List<Item> roots) {
    Set<String> containsUse = new HashSet<>();
    for (Item item : roots) {
      item.collectIdsContainingUse(containsUse);
    }
    roots.removeIf(c -> c.isUseOf(containsUse));
    for (Item item : roots) {
      item.removeUsesOf(containsUse);
    }
  }

  private static boolean inSvgNamespace(Element e) {
    // xmlns를 빼먹은 SVG도 받는다. 다른 네임스페이스(XHTML 등)는 받지 않는다
    return e.getNamespaceURI() == null || SVG_NS.equals(e.getNamespaceURI());
  }

  private static final class Counter {
    int elements;
  }

  /** 정리한 요소. 직접 문자열로 써서 받은 문서의 선언·네임스페이스가 따라오지 않게 한다. */
  private record Item(String name, Map<String, String> attrs, List<Item> children) {

    boolean collectIdsContainingUse(Set<String> out) {
      boolean hasUse = "use".equals(name);
      for (Item c : children) {
        hasUse |= c.collectIdsContainingUse(out);
      }
      if (hasUse && attrs.containsKey("id")) {
        out.add(attrs.get("id"));
      }
      return hasUse;
    }

    boolean isUseOf(Set<String> ids) {
      return "use".equals(name) && ids.contains(attrs.get("href").substring(1));
    }

    void removeUsesOf(Set<String> ids) {
      children.removeIf(c -> c.isUseOf(ids));
      for (Item c : children) {
        c.removeUsesOf(ids);
      }
    }

    void write(StringBuilder out) {
      out.append('<').append(name);
      attrs.forEach(
          (k, v) -> out.append(' ').append(k).append("=\"").append(escape(v)).append('"'));
      if (children.isEmpty()) {
        out.append("/>");
        return;
      }
      out.append('>');
      for (Item c : children) {
        c.write(out);
      }
      out.append("</").append(name).append('>');
    }
  }

  private static String escape(String v) {
    return v.replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }
}
