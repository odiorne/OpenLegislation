package gov.nysenate.openleg.service.scraping;

import gov.nysenate.openleg.dao.bill.text.BillTextReferenceDao;
import gov.nysenate.openleg.model.base.SessionYear;
import gov.nysenate.openleg.model.bill.BaseBillId;
import gov.nysenate.openleg.model.bill.BillId;
import gov.nysenate.openleg.model.entity.Chamber;
import gov.nysenate.openleg.model.spotcheck.billtext.BillTextReference;
import gov.nysenate.openleg.processor.base.ParseError;
import gov.nysenate.openleg.util.DateUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by kyle on 3/10/15.
 */
@Service
public class ScrapedBillTextParser {

    private static final Pattern scrapedBillFilePattern = Pattern.compile("^(\\d{4})-([A-z]\\d+)-(\\d{8}T\\d{6}).html$");

    private static final Pattern billIdPattern = Pattern.compile("^([A-z]\\d+)(?:-([A-z]))?$");

    private static final Pattern resolutionStartPattern = Pattern.compile("^\n[ ]+([A-Z]{2,})");

    /**
     * Parses a scraped bill file into a bill text reference containing an active amendment, full text, and a sponsor memo
     * @param file File
     * @return BillTextReference
     * @throws IOException if there are troubles reading the file
     * @throws ParseError if there are troubles while parsing the file
     */
    public BillTextReference parseReference(File file) throws IOException, ParseError{
        Matcher filenameMatcher = scrapedBillFilePattern.matcher(file.getName());
        if (filenameMatcher.matches()) {
            BaseBillId baseBillId = new BaseBillId(filenameMatcher.group(2), Integer.parseInt(filenameMatcher.group(1)));
            LocalDateTime referenceDateTime = LocalDateTime.parse(filenameMatcher.group(3), DateUtils.BASIC_ISO_DATE_TIME);

            Document document = Jsoup.parse(file, "UTF-8");
            BillId billId = getBillId(document, baseBillId.getSession());
            String text = getText(document, baseBillId);
            String memo = getMemo(document, baseBillId);

            return new BillTextReference(billId, referenceDateTime, text, memo);
        }
        throw new ParseError("Could not parse scraped bill filename: " + file.getName());
    }

    private BillId getBillId(Document document, SessionYear sessionYear) throws ParseError {
        Element printNoEle = document.select("span.nv_bot_info > strong").first();
        Matcher printNoMatcher = billIdPattern.matcher(printNoEle.text());
        if (printNoMatcher.matches()) {
            String basePrintNo = printNoMatcher.group(1);
            String version = printNoMatcher.group(2);
            return new BillId(basePrintNo + (version!=null ? version : ""), sessionYear);
        }
        throw new ParseError("could not parse scraped bill print no: " + printNoEle.text());
    }

    private String getText(Document document, BaseBillId baseBillId) {
        Element contents = document.getElementById("nv_bot_contents");
        Elements textEles = new Elements();

        for (Element element : contents.children()) {
            if ("pre".equalsIgnoreCase(element.tagName())) {
                textEles.add(element);
            } else if ("hr".equalsIgnoreCase(element.tagName()) && element.classNames().contains("noprint")) {
                break;
            }
        }

        StringBuilder textBuilder = new StringBuilder();

        textEles.forEach(ele -> processTextNode(ele, textBuilder));

        return formatBillText(textBuilder.toString(), baseBillId);
    }

    private String formatBillText(String billText, BaseBillId billId) {
        billText = billText.replaceAll("[\r\\uFEFF-\\uFFFF]|(?<=\n) ", "");
        billText = billText.replaceAll("§", "S");
        if (billId.getBillType().isResolution()) {
            billText = billText.replaceFirst("^\n\n[\\w \\.-]+\n\n[\\w \\.-:]+\n", "");
            Matcher resoStartMatcher = resolutionStartPattern.matcher(billText);
            if (resoStartMatcher.matches()) {
                billText = billText.replaceFirst(resolutionStartPattern.pattern(),
                        "\nLEGISLATIVE RESOLUTION " + resoStartMatcher.group(1).toLowerCase());
            }
            billText = billText.replaceFirst("^\n[ ]+PROVIDING", String.format("\n%s RESOLUTION providing", billId.getChamber()));
        } else {
            billText = billText.replaceFirst("^\n\n[ ]{12}STATE OF NEW YORK(?=\n)",
                    "\n                           S T A T E   O F   N E W   Y O R K");
            billText = billText.replaceFirst("(?<=\\n)[ ]{16}IN SENATE(?=\\n)",
                    "                                   I N  S E N A T E");
            billText = billText.replaceFirst("(?<=\\n)[ ]{15}IN ASSEMBLY(?=\\n)",
                    "                                 I N  A S S E M B L Y");
            billText = billText.replaceFirst("(?<=\\n)[ ]{12}SENATE - ASSEMBLY(?=\\n)",
                    "                             S E N A T E - A S S E M B L Y");
        }
        return billText;
    }

    private String getMemo(Document document, BaseBillId baseBillId) {
        Element memoEle = document.select("pre:last-of-type").first(); // you are the first and last of your kind
        // Do not get memo if bill is a resolution
        if (!baseBillId.getBillType().isResolution()) {
            StringBuilder memoBuilder = new StringBuilder();
            processTextNode(memoEle, memoBuilder);
            // todo format text
            return memoBuilder.toString();
        }
        return "";
    }

    private void processTextNode(Element ele, StringBuilder stringBuilder) {
        for (Node t : ele.childNodes()) {
            if (t instanceof Element) {
                Element e = (Element) t;
                if ("u".equals(e.tag().getName())) {
                    stringBuilder.append(e.text().toUpperCase());
                } else {
                    processTextNode(e, stringBuilder);
                }
            } else if (t instanceof TextNode) {
                stringBuilder.append(((TextNode) t).getWholeText());
            }
        }
    }
}
