package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootApplication
@RestController
@CrossOrigin(origins = "*")
public class DemoApplication {

    private final OrderRepository orderRepository;
    private final LicenseService licenseService;
    // स्क्रीन से जो UPI ID सेट की जाएगी वही यहाँ सुरक्षित रहेगी
    private String merchantUpiId = "";

    public DemoApplication(OrderRepository orderRepository, LicenseService licenseService) {
        this.orderRepository = orderRepository;
        this.licenseService = licenseService;
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    // 0. एजेंट कंट्रोल पैनल सीधे लोड करने के लिए डायरेक्ट रूट
    @GetMapping(value = {"/agent", "/agent.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> getAgentPanel() {
        return ResponseEntity.ok(new ClassPathResource("static/agent.html"));
    }

    // 0.1 मेन चेकआउट पेज सीधे लोड करने के लिए डायरेक्ट रूट
    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> getIndexPage() {
        return ResponseEntity.ok(new ClassPathResource("static/index.html"));
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

    // 3. नया ऑर्डर और यूनिक डिस्काउंट अमाउंट जनरेट करने की API (Anti-Collision Lock + Remote Kill Switch)
    @PostMapping("/api/payment/create-order")
    public Map<String, Object> createOrder(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> res = new HashMap<>();

        // रिमोट लाइसेंस चेक: अगर स्टेटस BLOCKED है तो नया पेमेंट रोक दें
        if (!licenseService.isServiceActive()) {
            res.put("status", "SERVICE_SUSPENDED");
            res.put("message", "Service has been temporarily suspended. Please contact administrator.");
            System.out.println(">>> BLOCKED ATTEMPT: Order creation rejected due to inactive license.");
            return res;
        }

        Double baseAmount = 10.0;
        try {
            if (payload != null && payload.containsKey("amount") && payload.get("amount") != null) {
                baseAmount = Double.valueOf(payload.get("amount").toString());
            }
        } catch (Exception e) {
            baseAmount = 10.0;
        }

        // पिछले 20 मिनट के सक्रिय पेंडिंग ऑर्डर्स निकालें ताकि समान अमाउंट किसी और को न मिले
        List<PaymentOrder> pendingOrders = orderRepository.findByStatus("PENDING");
        Set<Double> busyAmounts = new HashSet<>();
        LocalDateTime twentyMinutesAgo = LocalDateTime.now().minusMinutes(20);

        for (PaymentOrder po : pendingOrders) {
            if (po.getCreatedAt() != null && po.getCreatedAt().isAfter(twentyMinutesAgo)) {
                busyAmounts.add(po.getAmount());
            }
        }

        // 1 से 50 पैसे के बीच ऐसा डिस्काउंट चुनें जो किसी अन्य सक्रिय ऑर्डर के पास न हो
        double finalAmount = baseAmount;
        int discountPaise = 0;
        int attempts = 0;
        Random rnd = new Random();

        do {
            discountPaise = rnd.nextInt(50) + 1; // 1 to 50 paise
            double discount = discountPaise / 100.0;
            finalAmount = Math.round((baseAmount - discount) * 100.0) / 100.0;
            attempts++;
        } while (busyAmounts.contains(finalAmount) && attempts < 50);

        if (finalAmount <= 0) {
            finalAmount = baseAmount;
            discountPaise = 0;
        }

        String upiToUse = (this.merchantUpiId != null && !this.merchantUpiId.isEmpty()) 
                          ? this.merchantUpiId : "";

        String orderId = "ORD" + System.currentTimeMillis();

        PaymentOrder order = new PaymentOrder();
        order.setOrderId(orderId);
        order.setAmount(finalAmount);
        order.setStatus("PENDING");
        order.setCustomerUtr("Not submitted");
        order.setCreatedAt(LocalDateTime.now());

        orderRepository.save(order);

        System.out.println(">>> CREATED ORDER: " + orderId + " | BASE: ₹" + baseAmount 
                           + " | FINAL: ₹" + finalAmount + " (DISCOUNT: " + discountPaise + " PAISE)");

        res.put("orderId", orderId);
        res.put("id", orderId);
        res.put("amount", finalAmount);
        res.put("originalAmount", baseAmount);
        res.put("discountPaise", discountPaise);
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
            res.put("status", order.getStatus());
            res.put("amount", order.getAmount());
            res.put("customerUtr", order.getCustomerUtr());
        } else {
            res.put("status", "NOT_FOUND");
        }
        return res;
    }

    // 5. मैन्युअल UTR सबमिशन API (वैकल्पिक बैकअप)
    @PostMapping("/api/payment/submit-utr")
    public Map<String, Object> submitUtr(@RequestBody Map<String, String> payload) {
        Map<String, Object> res = new HashMap<>();
        String orderId = payload.get("orderId");
        String utr = payload.get("utr");

        Optional<PaymentOrder> opt = orderRepository.findById(orderId);
        if (opt.isPresent()) {
            PaymentOrder order = opt.get();
            order.setCustomerUtr(utr);
            orderRepository.save(order);
            res.put("status", "SUCCESS");
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
            res.put("status", "SUCCESS");
        } else {
            res.put("status", "NOT_FOUND");
        }
        return res;
    }

    // 8. 100% यूनिवर्सल SMS वेबहुक (किसी भी भारतीय बैंक के लिए)
    @PostMapping(value = {"/api/sms/webhook", "/api/payments/sms-webhook"})
    public Map<String, Object> handleBankSms(@RequestBody(required = false) String rawBody,
                                            @RequestParam Map<String, String> allParams) {
        Map<String, Object> res = new HashMap<>();

        // रिमोट लाइसेंस चेक: अगर स्टेटस BLOCKED है तो SMS प्रोसेस न करें
        if (!licenseService.isServiceActive()) {
            res.put("status", "SERVICE_SUSPENDED");
            System.out.println(">>> BLOCKED ATTEMPT: Incoming SMS webhook ignored due to inactive license.");
            return res;
        }

        String message = "";

        // URL Parameters में से SMS ढूँढें
        if (allParams != null && !allParams.isEmpty()) {
            for (String key : new String[]{"text", "message", "msg", "content", "body"}) {
                if (allParams.containsKey(key)) {
                    message = allParams.get(key);
                    break;
                }
            }
        }

        // अगर JSON Body के अंदर आया हो
        if ((message == null || message.trim().isEmpty()) && rawBody != null) {
            Pattern p = Pattern.compile("\"(?:text|message|msg|content|body)\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
            Matcher m = p.matcher(rawBody);
            if (m.find()) {
                message = m.group(1).replace("\\n", " ").replace("\\\"", "\"");
            } else {
                message = rawBody;
            }
        }

        System.out.println("==========================================");
        System.out.println(">>> RAW INCOMING SMS: " + message);
        System.out.println("==========================================");

        if (message == null || message.trim().isEmpty()) {
            res.put("status", "IGNORED_EMPTY");
            return res;
        }

        String lower = message.toLowerCase();

        // 1. डेबिट (पैसे कटने) के SMS को तुरंत छोड़ दें
        if (lower.contains("sent rs") || lower.contains("dr. from") || lower.contains("debited") || lower.contains("dr from")) {
            System.out.println(">>> IGNORED: Debit / Sent SMS detected.");
            res.put("status", "IGNORED_DEBIT");
            return res;
        }

        // 2. अमाउंट निकालना (Rs., INR, ₹ के बाद लिखी संख्या)
        Double amount = null;
        Pattern amtPat = Pattern.compile("(?:rs\\.?|inr|₹)\\s*([0-9]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
        Matcher amtMat = amtPat.matcher(message);
        if (amtMat.find()) {
            try {
                amount = Double.parseDouble(amtMat.group(1));
            } catch (Exception ignored) {}
        }

        // 3. पूरे SMS में मौजूद 12 अंकों का UTR निकालना
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

        System.out.println(">>> PARSED -> AMOUNT: ₹" + amount + " | UTR: " + utr);

        // 4. पेंडिंग ऑर्डर से अमाउंट मैच करना और ऑटो-वेरिफाई करना (अधिकतम 25 मिनट पुराना ऑर्डर ही लें)
        if (amount != null) {
            List<PaymentOrder> pendingOrders = orderRepository.findByStatus("PENDING");
            LocalDateTime allowedTimeLimit = LocalDateTime.now().minusMinutes(25);

            for (int i = pendingOrders.size() - 1; i >= 0; i--) {
                PaymentOrder order = pendingOrders.get(i);
                
                // 1 पैसे से कम का अंतर यानी सही मैच
                if (Math.abs(order.getAmount() - amount) < 0.01) {
                    // अगर ऑर्डर पिछले 25 मिनट के अंदर बना है तभी पास करें
                    if (order.getCreatedAt() == null || order.getCreatedAt().isAfter(allowedTimeLimit)) {
                        order.setStatus("PAID SUCCESSFULLY");
                        if (utr != null && !utr.isEmpty()) {
                            order.setCustomerUtr(utr);
                        }
                        order.setVerifiedBy("SMS_AUTOMATION");
                        orderRepository.save(order);

                        System.out.println(">>> AUTO-VERIFIED ORDER ID: " + order.getOrderId() + " WITH UTR: " + utr);
                        res.put("status", "SUCCESS");
                        res.put("orderId", order.getOrderId());
                        res.put("utr", utr);
                        return res;
                    } else {
                        System.out.println(">>> ORDER EXPIRED: Order ID " + order.getOrderId() + " is older than 25 minutes.");
                    }
                }
            }
        }

        res.put("status", "PROCESSED_NO_MATCH");
        return res;
    }
}

@Entity
@Table(name = "payment_orders")
class PaymentOrder {
    @Id
    private String orderId;
    private Double amount;
    private String status;
    private String customerUtr;
    private String verifiedBy;
    private LocalDateTime createdAt;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCustomerUtr() { return customerUtr; }
    public void setCustomerUtr(String customerUtr) { this.customerUtr = customerUtr; }

    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

@Repository
interface OrderRepository extends JpaRepository<PaymentOrder, String> {
    List<PaymentOrder> findByStatus(String status);
}
