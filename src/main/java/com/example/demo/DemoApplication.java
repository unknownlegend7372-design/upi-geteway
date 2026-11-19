package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootApplication
@RestController
@CrossOrigin(origins = "*")
public class DemoApplication {

    private final OrderRepository orderRepository;
    private final SmsLogRepository smsLogRepository;
    private final LicenseService licenseService;
    private String merchantUpiId = "";

    // अपना Flask रिचार्ज ऐप का बेस URL/डोमेन यहाँ सेट करें
    private static final String DEFAULT_RECHARGE_NOTIFY_URL = "http://localhost:5000/recharge/gateway-notify";

    public DemoApplication(OrderRepository orderRepository, SmsLogRepository smsLogRepository, LicenseService licenseService) {
        this.orderRepository = orderRepository;
        this.smsLogRepository = smsLogRepository;
        this.licenseService = licenseService;
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    // 1. स्क्रीन से UPI ID सेट करने के लिए API
    @PostMapping("/api/admin/set-upi")
    public Map<String, Object> setMerchantUpi(@RequestBody Map<String, String> payload) {
        Map<String, Object> res = new HashMap<>();
        String newUpi = payload.get("upiId");
        if (newUpi != null && !newUpi.trim().isEmpty()) {
            this.merchantUpiId = newUpi.trim();
            res.put("status", "SUCCESS");
            res.put("upiId", this.merchantUpiId);
            System.out.println(">>> MERCHANT UPI UPDATED: " + this.merchantUpiId);
        } else {
            res.put("status", "ERROR");
        }
        return res;
    }

    // 2. सेव की गई UPI ID प्राप्त करने के लिए API
    @GetMapping("/api/admin/get-upi")
    public Map<String, Object> getMerchantUpi() {
        Map<String, Object> res = new HashMap<>();
        res.put("upiId", this.merchantUpiId);
        return res;
    }

    // 3. नया ऑर्डर जनरेट करने की API (EXACT AMOUNT - ₹0.00 LOSS, 3-MIN WINDOW)
    @PostMapping("/api/payment/create-order")
    public Map<String, Object> createOrder(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> res = new HashMap<>();

        if (!licenseService.isServiceActive()) {
            res.put("status", "SERVICE_SUSPENDED");
            res.put("message", "Service has been temporarily suspended. Please contact administrator.");
            return res;
        }

        Double baseAmount = 10.0;
        String localOrderId = "";
        String notifyUrl = "";

        try {
            if (payload != null) {
                if (payload.containsKey("amount") && payload.get("amount") != null) {
                    baseAmount = Double.valueOf(payload.get("amount").toString());
                }
                if (payload.get("local_order_id") != null) {
                    localOrderId = payload.get("local_order_id").toString().trim();
                } else if (payload.get("localOrderId") != null) {
                    localOrderId = payload.get("localOrderId").toString().trim();
                }

                if (payload.get("notify_url") != null) {
                    notifyUrl = payload.get("notify_url").toString().trim();
                } else if (payload.get("notifyUrl") != null) {
                    notifyUrl = payload.get("notifyUrl").toString().trim();
                }
            }
        } catch (Exception e) {
            baseAmount = 10.0;
        }

        // पूरा Exact Amount रहेगा (₹0.00 लॉस)
        double finalAmount = Math.round(baseAmount * 100.0) / 100.0;

        // पिछले 3 मिनट के सक्रिय पेंडिंग ऑर्डर्स चेक करें (चाहे 2 हों या 100 कस्टमर)
        List<PaymentOrder> pendingOrders = orderRepository.findByStatus("PENDING");
        LocalDateTime threeMinutesAgo = LocalDateTime.now().minusMinutes(3);
        int activeSameAmountCount = 0;

        for (PaymentOrder po : pendingOrders) {
            if (po.getCreatedAt() != null && po.getCreatedAt().isAfter(threeMinutesAgo)) {
                if (Math.abs(po.getAmount() - finalAmount) < 0.01) {
                    activeSameAmountCount++;
                }
            }
        }

        boolean requireUtr = activeSameAmountCount > 0;
        String upiToUse = (this.merchantUpiId != null && !this.merchantUpiId.isEmpty())
                          ? this.merchantUpiId : "";

        String orderId = "ORD" + System.currentTimeMillis() + (new Random().nextInt(90) + 10);

        PaymentOrder order = new PaymentOrder();
        order.setOrderId(orderId);
        order.setLocalOrderId(localOrderId);
        order.setNotifyUrl(notifyUrl);
        order.setAmount(finalAmount);
        order.setStatus("PENDING");
        order.setCustomerUtr("Not submitted");
        order.setRequireUtr(requireUtr);
        order.setCreatedAt(LocalDateTime.now());

        orderRepository.save(order);

        res.put("orderId", orderId);
        res.put("id", orderId);
        res.put("localOrderId", localOrderId);
        res.put("amount", finalAmount);
        res.put("originalAmount", baseAmount);
        res.put("discountPaise", 0);
        res.put("requireUtr", requireUtr);
        res.put("upiId", upiToUse);
        res.put("status", "PENDING");
        return res;
    }

    // 4. ऑर्डर स्टेटस चेक API
    @GetMapping("/api/payment/status/{orderId}")
    public Map<String, Object> checkStatus(@PathVariable String orderId) {
        Map<String, Object> res = new HashMap<>();
        Optional<PaymentOrder> opt = orderRepository.findById(orderId);
        if (opt.isPresent()) {
            PaymentOrder order = opt.get();
            res.put("orderId", order.getOrderId());
            res.put("id", order.getOrderId());
            res.put("localOrderId", order.getLocalOrderId());
            res.put("status", order.getStatus());
            res.put("amount", order.getAmount());
            res.put("requireUtr", order.isRequireUtr());
            res.put("customerUtr", order.getCustomerUtr());
        } else {
            res.put("status", "NOT_FOUND");
        }
        return res;
    }

    // 5. कस्टमर द्वारा UTR सबमिट करते ही इंस्टेंट मैचिंग (3 मिनट बाद भी काम करेगा)
    @PostMapping("/api/payment/submit-utr")
    public Map<String, Object> submitUtr(@RequestBody Map<String, String> payload) {
        Map<String, Object> res = new HashMap<>();
        String orderId = payload.get("orderId");
        String utr = payload.get("utr") != null ? payload.get("utr").trim() : "";

        if (utr.length() != 12 || !utr.matches("\\d{12}")) {
            res.put("status", "INVALID_UTR");
            res.put("message", "Please enter a valid 12-digit UTR number.");
            return res;
        }

        // चेक करें कि यह UTR पहले किसी दूसरे सफल ऑर्डर में इस्तेमाल तो नहीं हुआ
        List<PaymentOrder> allOrders = orderRepository.findAll();
        for (PaymentOrder existing : allOrders) {
            if (utr.equals(existing.getCustomerUtr()) && "PAID SUCCESSFULLY".equals(existing.getStatus())) {
                res.put("status", "UTR_ALREADY_USED");
                res.put("message", "This UTR number has already been used.");
                return res;
            }
        }

        Optional<PaymentOrder> opt = orderRepository.findById(orderId);
        if (opt.isPresent()) {
            PaymentOrder order = opt.get();
            order.setCustomerUtr(utr);

            // जाँचें कि क्या इस UTR का बैंक SMS पहले ही आ चुका है (बिना किसी टाइम-लिमिट के)
            Optional<BankSmsLog> smsOpt = smsLogRepository.findById(utr);
            if (smsOpt.isPresent()) {
                BankSmsLog sms = smsOpt.get();
                if (!sms.isUsed() && Math.abs(sms.getAmount() - order.getAmount()) < 0.01) {
                    order.setStatus("PAID SUCCESSFULLY");
                    order.setVerifiedBy("UTR_INSTANT_MATCH (" + sms.getSenderName() + ")");
                    sms.setUsed(true);
                    smsLogRepository.save(sms);
                }
            }

            orderRepository.save(order);
            notifyRechargeIfPaid(order);
            res.put("status", "SUCCESS");
            res.put("orderStatus", order.getStatus());
        } else {
            res.put("status", "NOT_FOUND");
        }
        return res;
    }

    // 6. डैशबोर्ड के लिए सभी ऑर्डर्स
    @GetMapping("/api/admin/orders")
    public List<PaymentOrder> getAllOrders() {
        return orderRepository.findAll();
    }

    // 7. मैन्युअल अप्रूव/रिजेक्ट API
    @PostMapping("/api/admin/update-status")
    public Map<String, Object> updateStatus(@RequestBody Map<String, String> payload) {
        Map<String, Object> res = new HashMap<>();
        String orderId = payload.get("orderId");
        String status = payload.get("status");

        Optional<PaymentOrder> opt = orderRepository.findById(orderId);
        if (opt.isPresent()) {
            PaymentOrder order = opt.get();
            order.setStatus(status);
            order.setVerifiedBy("AGENT_MANUAL");
            orderRepository.save(order);
            notifyRechargeIfPaid(order);
            res.put("status", "SUCCESS");
        } else {
            res.put("status", "NOT_FOUND");
        }
        return res;
    }

    // 8. यूनिवर्सल CREDIT-ONLY SMS वेबहुक (कोई अकाउंट नंबर फिक्स नहीं, 3-मिनट ऑटो विंडो)
    @PostMapping(value = {"/api/sms/webhook", "/api/payments/sms-webhook"})
    public Map<String, Object> handleBankSms(@RequestBody(required = false) String rawBody,
                                            @RequestParam Map<String, String> allParams) {
        Map<String, Object> res = new HashMap<>();

        if (!licenseService.isServiceActive()) {
            res.put("status", "SERVICE_SUSPENDED");
            return res;
        }

        String message = "";

        if (allParams != null && !allParams.isEmpty()) {
            for (String key : new String[]{"text", "message", "msg", "content", "body"}) {
                if (allParams.containsKey(key)) {
                    message = allParams.get(key);
                    break;
                }
            }
        }

        if ((message == null || message.trim().isEmpty()) && rawBody != null) {
            Pattern p = Pattern.compile("\"(?:text|message|msg|content|body)\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
            Matcher m = p.matcher(rawBody);
            if (m.find()) {
                message = m.group(1).replace("\\n", " ").replace("\\\"", "\"");
            } else {
                message = rawBody;
            }
        }

        if (message == null || message.trim().isEmpty()) {
            res.put("status", "IGNORED_EMPTY");
            return res;
        }

        String lower = message.toLowerCase();

        // नियम 1: कोई भी Debit / Sent / Paid मैसेज तुरंत ब्लॉक करें
        if (lower.contains("debited") || lower.contains("sent") || lower.contains("paid") ||
            lower.contains("withdrawn") || lower.contains("dr. from") || lower.contains("dr from")) {
            res.put("status", "IGNORED_DEBIT");
            return res;
        }

        // नियम 2: केवल Credit / Received मैसेज को ही स्वीकार करें
        if (!lower.contains("received") && !lower.contains("credited") && !lower.contains("cr. to") && !lower.contains("cr to")) {
            res.put("status", "IGNORED_NON_CREDIT");
            return res;
        }

        // 3. अमाउंट निकालना
        Double amount = null;
        Pattern amtPat = Pattern.compile("(?:rs\\.?|inr|₹)\\s*([0-9]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
        Matcher amtMat = amtPat.matcher(message);
        if (amtMat.find()) {
            try {
                amount = Double.parseDouble(amtMat.group(1));
            } catch (Exception ignored) {}
        }

        // 4. 12 अंकों का UTR निकालना
        String utr = null;
        Pattern utrPat = Pattern.compile("(?:upi\\s*ref[:\\s]*|ref[:\\s]*|utr[:\\s]*|rrn[:\\s]*)([0-9]{12})", Pattern.CASE_INSENSITIVE);
        Matcher utrMat = utrPat.matcher(message);
        if (utrMat.find()) {
            utr = utrMat.group(1);
        } else {
            Pattern fallbackPat = Pattern.compile("\\b([0-9]{12})\\b");
            Matcher fallbackMat = fallbackPat.matcher(message);
            if (fallbackMat.find()) {
                utr = fallbackMat.group(1);
            }
        }

        // 5. भेजने वाले का नाम निकालना
        String senderName = "Unknown";
        Pattern namePat = Pattern.compile("from\\s+([A-Za-z0-9\\s.@_-]+?)\\s+(?:on|via|ref|upi)", Pattern.CASE_INSENSITIVE);
        Matcher nameMat = namePat.matcher(message);
        if (nameMat.find()) {
            senderName = nameMat.group(1).trim();
        }

        if (amount == null) {
            res.put("status", "IGNORED_NO_AMOUNT");
            return res;
        }

        // डुप्लीकेट UTR SMS ब्लॉक करें
        if (utr != null) {
            Optional<BankSmsLog> existingSms = smsLogRepository.findById(utr);
            if (existingSms.isPresent() && existingSms.get().isUsed()) {
                res.put("status", "DUPLICATE_UTR_SMS");
                return res;
            }
        }

        List<PaymentOrder> pendingOrders = orderRepository.findByStatus("PENDING");

        // प्राथमिकता 1 (100 कस्टमर या देर से UTR डालने वाला केस): जिस कस्टमर ने यह UTR डाल रखा है, सीधे उसका ऑर्डर पास करें (भले ही 3 मिनट से ज्यादा हो गए हों)
        if (utr != null) {
            for (PaymentOrder order : pendingOrders) {
                if (utr.equals(order.getCustomerUtr()) && Math.abs(order.getAmount() - amount) < 0.01) {
                    order.setStatus("PAID SUCCESSFULLY");
                    order.setVerifiedBy("SMS_UTR_MATCH (" + senderName + ")");
                    orderRepository.save(order);
                    notifyRechargeIfPaid(order);

                    saveSmsLog(utr, amount, senderName, true);
                    res.put("status", "SUCCESS");
                    res.put("orderId", order.getOrderId());
                    res.put("utr", utr);
                    return res;
                }
            }
        }

        // प्राथमिकता 2 (सिंगल कस्टमर केस): पिछले 3 मिनट में इसी रकम का सिर्फ़ 1 ही सक्रिय ऑर्डर है, तो ऑटो-अप्रूव करें
        LocalDateTime allowedTimeLimit = LocalDateTime.now().minusMinutes(3);
        List<PaymentOrder> matchingAmountOrders = new ArrayList<>();

        for (PaymentOrder order : pendingOrders) {
            if (Math.abs(order.getAmount() - amount) < 0.01) {
                if (order.getCreatedAt() == null || order.getCreatedAt().isAfter(allowedTimeLimit)) {
                    matchingAmountOrders.add(order);
                }
            }
        }

        if (matchingAmountOrders.size() == 1) {
            PaymentOrder singleOrder = matchingAmountOrders.get(0);
            singleOrder.setStatus("PAID SUCCESSFULLY");
            if (utr != null) {
                singleOrder.setCustomerUtr(utr);
            }
            singleOrder.setVerifiedBy("SMS_AUTO_EXACT (" + senderName + ")");
            orderRepository.save(singleOrder);
            notifyRechargeIfPaid(singleOrder);

            if (utr != null) {
                saveSmsLog(utr, amount, senderName, true);
            }

            res.put("status", "SUCCESS");
            res.put("orderId", singleOrder.getOrderId());
            res.put("utr", utr);
            return res;
        }

        // प्राथमिकता 3: SMS को सुरक्षित सेव करें ताकि कस्टमर जब भी UTR डाले (3 मिनट के अंदर या बाद में), तुरंत अप्रूव हो जाए
        if (utr != null) {
            saveSmsLog(utr, amount, senderName, false);
        }

        res.put("status", matchingAmountOrders.size() > 1 ? "MULTIPLE_ORDERS_WAIT_UTR" : "SAVED_NO_PENDING_ORDER");
        return res;
    }

    private void notifyRechargeIfPaid(PaymentOrder order) {
        if (order == null || !"PAID SUCCESSFULLY".equals(order.getStatus())) {
            return;
        }
        if (order.getLocalOrderId() == null || order.getLocalOrderId().trim().isEmpty()) {
            return;
        }

        String targetUrl = (order.getNotifyUrl() != null && !order.getNotifyUrl().trim().isEmpty())
                ? order.getNotifyUrl().trim()
                : DEFAULT_RECHARGE_NOTIFY_URL;

        String utrVal = (order.getCustomerUtr() != null && !"Not submitted".equals(order.getCustomerUtr()))
                ? order.getCustomerUtr() : "";

        String jsonBody = String.format(
            "{\"local_order_id\":\"%s\",\"gateway_order_id\":\"%s\",\"order_id\":\"%s\",\"status\":\"PAID\",\"amount\":%s,\"utr\":\"%s\"}",
            escapeJson(order.getLocalOrderId()),
            escapeJson(order.getOrderId()),
            escapeJson(order.getOrderId()),
            order.getAmount() != null ? order.getAmount() : 0.0,
            escapeJson(utrVal)
        );

        CompletableFuture.runAsync(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                System.out.println(">>> RECHARGE NOTIFY [" + order.getLocalOrderId() + "] -> " + resp.statusCode() + " : " + resp.body());
            } catch (Exception e) {
                System.out.println(">>> RECHARGE NOTIFY ERR: " + e.getMessage());
            }
        });
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void saveSmsLog(String utr, Double amount, String senderName, boolean used) {
        BankSmsLog log = new BankSmsLog();
        log.setUtr(utr);
        log.setAmount(amount);
        log.setSenderName(senderName);
        log.setUsed(used);
        log.setReceivedAt(LocalDateTime.now());
        smsLogRepository.save(log);
    }
}

@Entity
@Table(name = "payment_orders")
class PaymentOrder {
    @Id
    private String orderId;
    private String localOrderId;
    private String notifyUrl;
    private Double amount;
    private String status;
    private String customerUtr;
    private String verifiedBy;
    private boolean requireUtr;
    private LocalDateTime createdAt;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getLocalOrderId() { return localOrderId; }
    public void setLocalOrderId(String localOrderId) { this.localOrderId = localOrderId; }

    public String getNotifyUrl() { return notifyUrl; }
    public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCustomerUtr() { return customerUtr; }
    public void setCustomerUtr(String customerUtr) { this.customerUtr = customerUtr; }

    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }

    public boolean isRequireUtr() { return requireUtr; }
    public void setRequireUtr(boolean requireUtr) { this.requireUtr = requireUtr; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

@Entity
@Table(name = "bank_sms_logs")
class BankSmsLog {
    @Id
    private String utr;
    private Double amount;
    private String senderName;
    private boolean used;
    private LocalDateTime receivedAt;

    public String getUtr() { return utr; }
    public void setUtr(String utr) { this.utr = utr; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }

    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}

@Repository
interface OrderRepository extends JpaRepository<PaymentOrder, String> {
    List<PaymentOrder> findByStatus(String status);
}

@Repository
interface SmsLogRepository extends JpaRepository<BankSmsLog, String> {
}
