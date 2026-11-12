package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.MediaType;
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
    private String merchantUpiId = "";

    public DemoApplication(OrderRepository orderRepository, LicenseService licenseService) {
        this.orderRepository = orderRepository;
        this.licenseService = licenseService;
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    // 100% गारंटीड एजेंट पैनल (बिना किसी फाइल पर निर्भर रहे सीधे Java से रेंडर होगा)
    @GetMapping(value = {"/agent", "/agent.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public String getDirectAgentPanel() {
        return """
        <!DOCTYPE html>
        <html lang="hi">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Agent Control Panel</title>
            <style>
                body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f8fafc; margin: 0; padding: 20px; }
                .container { max-width: 900px; margin: auto; background: white; padding: 25px; border-radius: 12px; box-shadow: 0 4px 20px rgba(0,0,0,0.06); }
                .auth-box { max-width: 360px; margin: 60px auto; background: white; padding: 30px; border-radius: 12px; box-shadow: 0 4px 20px rgba(0,0,0,0.08); text-align: center; }
                .top-bar { display: flex; gap: 20px; background: #f1f5f9; padding: 15px; border-radius: 8px; margin-bottom: 25px; align-items: flex-end; flex-wrap: wrap; }
                .form-group { display: flex; flex-direction: column; flex: 1; min-width: 200px; }
                .form-group label { font-size: 13px; font-weight: 600; margin-bottom: 6px; color: #475569; text-align: left; }
                input[type="text"], input[type="password"] { padding: 9px 12px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 14px; outline: none; box-sizing: border-box; width: 100%; }
                button { background-color: #2563eb; color: white; border: none; padding: 9px 18px; border-radius: 6px; cursor: pointer; font-weight: 600; }
                button:hover { background-color: #1d4ed8; }
                table { width: 100%; border-collapse: collapse; margin-top: 15px; font-size: 13px; }
                th, td { border: 1px solid #e2e8f0; padding: 10px 8px; text-align: center; }
                th { background-color: #f8fafc; color: #334155; }
                .badge { display: inline-block; padding: 4px 10px; border-radius: 20px; font-weight: 700; font-size: 12px; }
                .status-PENDING { background-color: #fef3c7; color: #d97706; }
                .status-SUCCESS { background-color: #dcfce7; color: #15803d; }
                .status-REJECTED { background-color: #fee2e2; color: #b91c1c; }
                .btn-approve { background-color: #16a34a; padding: 5px 10px; font-size: 12px; border-radius: 4px; margin-right: 4px; color: white; border: none; cursor: pointer; }
                .btn-reject { background-color: #dc2626; padding: 5px 10px; font-size: 12px; border-radius: 4px; color: white; border: none; cursor: pointer; }
                #adminDashboard { display: none; }
            </style>
        </head>
        <body>
        <div class="auth-box" id="authBox">
            <h3 style="margin-top:0;">🔐 Agent Access</h3>
            <p style="font-size:13px; color:#64748b;">कंट्रोल पैनल खोलने के लिए पिन दर्ज करें:</p>
            <input type="password" id="agentPin" placeholder="Enter PIN" style="margin-bottom: 12px;">
            <button style="width: 100%;" onclick="verifyPin()">Unlock Panel</button>
        </div>

        <div class="container" id="adminDashboard">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px;">
                <h2 style="margin: 0; font-size: 20px;">Agent Control Panel</h2>
                <button onclick="logout()" style="background-color: #64748b; padding: 6px 12px; font-size: 12px;">Lock Panel</button>
            </div>

            <div class="top-bar">
                <div class="form-group">
                    <label>मर्चेंट नाम (Business / Game Name)</label>
                    <input type="text" id="merchantName" placeholder="दुकान या गेम का नाम">
                </div>
                <div class="form-group">
                    <label>पेमेंट पाने वाली UPI ID</label>
                    <input type="text" id="merchantUpi" placeholder="उदा. name@upi">
                </div>
                <button onclick="saveMerchantSettings()">Save Details</button>
            </div>

            <div style="display: flex; justify-content: space-between; align-items: center;">
                <h3 style="margin: 0;">Recent Transactions</h3>
                <button onclick="loadOrders()" style="background-color: #0284c7; padding: 6px 12px; font-size: 12px;">Refresh</button>
            </div>

            <table>
                <thead>
                    <tr>
                        <th>Order ID</th>
                        <th>Amount</th>
                        <th>Customer UTR</th>
                        <th>Status</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody id="ordersTableBody">
                    <tr><td colspan="5">No orders yet</td></tr>
                </tbody>
            </table>
        </div>

        <script>
            const SECRET_PIN = "1234";

            function verifyPin() {
                const entered = document.getElementById('agentPin').value;
                if (entered === SECRET_PIN) {
                    document.getElementById('authBox').style.display = 'none';
                    document.getElementById('adminDashboard').style.display = 'block';
                    initDashboard();
                } else {
                    alert('गलत पिन!');
                }
            }

            function logout() {
                document.getElementById('authBox').style.display = 'block';
                document.getElementById('adminDashboard').style.display = 'none';
                document.getElementById('agentPin').value = '';
            }

            function initDashboard() {
                const savedName = localStorage.getItem('app_merchant_name');
                const savedUpi = localStorage.getItem('app_merchant_upi');

                if (savedName) document.getElementById('merchantName').value = savedName;
                if (savedUpi) {
                    document.getElementById('merchantUpi').value = savedUpi;
                    fetch('/api/admin/set-upi', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ upiId: savedUpi })
                    }).catch(e => console.log(e));
                } else {
                    fetch('/api/admin/get-upi')
                        .then(res => res.json())
                        .then(data => {
                            if (data.upiId) document.getElementById('merchantUpi').value = data.upiId;
                        }).catch(err => console.log(err));
                }

                loadOrders();
                setInterval(loadOrders, 3000);
            }

            function saveMerchantSettings() {
                const name = document.getElementById('merchantName').value.trim();
                const upi = document.getElementById('merchantUpi').value.trim();

                if (!upi) return alert('कृपया वैध UPI ID दर्ज करें!');

                localStorage.setItem('app_merchant_name', name);
                localStorage.setItem('app_merchant_upi', upi);

                fetch('/api/admin/set-upi', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ upiId: upi })
                })
                .then(res => res.json())
                .then(() => alert('सेटिंग्स सुरक्षित हो गईं!'))
                .catch(err => alert('एरर: ' + err));
            }

            function updateAction(orderId, status) {
                fetch('/api/admin/update-status', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ orderId: orderId, status: status })
                }).then(res => res.json()).then(() => loadOrders());
            }

            function loadOrders() {
                fetch('/api/admin/orders')
                    .then(res => res.json())
                    .then(orders => {
                        const tbody = document.getElementById('ordersTableBody');
                        if (!orders || orders.length === 0) {
                            tbody.innerHTML = '<tr><td colspan="5">No orders yet</td></tr>';
                            return;
                        }
                        tbody.innerHTML = orders.slice().reverse().map(o => {
                            const isSuccess = (o.status || '').includes('PAID') || o.status === 'SUCCESS';
                            const isRejected = o.status === 'REJECTED';
                            const badgeClass = isSuccess ? 'status-SUCCESS' : (isRejected ? 'status-REJECTED' : 'status-PENDING');
                            const displayAmt = parseFloat(o.amount || 0).toFixed(2);

                            return `
                            <tr>
                                <td>${o.orderId || ''}</td>
                                <td>₹${displayAmt}</td>
                                <td>${o.customerUtr || 'Not submitted'}</td>
                                <td><span class="badge ${badgeClass}">${o.status}</span></td>
                                <td>
                                    <button class="btn-approve" onclick="updateAction('${o.orderId}', 'PAID SUCCESSFULLY')">Approve</button>
                                    <button class="btn-reject" onclick="updateAction('${o.orderId}', 'REJECTED')">Reject</button>
                                </td>
                            </tr>
                            `;
                        }).join('');
                    }).catch(err => console.log(err));
            }
        </script>
        </body>
        </html>
        """;
    }

    @PostMapping("/api/admin/set-upi")
    public Map<String, Object> setMerchantUpi(@RequestBody Map<String, String> payload) {
        Map<String, Object> res = new HashMap<>();
        String newUpi = payload.get("upiId");
        if (newUpi != null && !newUpi.trim().isEmpty()) {
            this.merchantUpiId = newUpi.trim();
            res.put("status", "SUCCESS");
            res.put("upiId", this.merchantUpiId);
        } else {
            res.put("status", "ERROR");
        }
        return res;
    }

    @GetMapping("/api/admin/get-upi")
    public Map<String, Object> getMerchantUpi() {
        Map<String, Object> res = new HashMap<>();
        res.put("upiId", this.merchantUpiId);
        return res;
    }

    @PostMapping("/api/payment/create-order")
    public Map<String, Object> createOrder(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> res = new HashMap<>();

        if (!licenseService.isServiceActive()) {
            res.put("status", "SERVICE_SUSPENDED");
            res.put("message", "Service has been temporarily suspended.");
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

        List<PaymentOrder> pendingOrders = orderRepository.findByStatus("PENDING");
        Set<Double> busyAmounts = new HashSet<>();
        LocalDateTime twentyMinutesAgo = LocalDateTime.now().minusMinutes(20);

        for (PaymentOrder po : pendingOrders) {
            if (po.getCreatedAt() != null && po.getCreatedAt().isAfter(twentyMinutesAgo)) {
                busyAmounts.add(po.getAmount());
            }
        }

        double finalAmount = baseAmount;
        int discountPaise = 0;
        int attempts = 0;
        Random rnd = new Random();

        do {
            discountPaise = rnd.nextInt(50) + 1;
            double discount = discountPaise / 100.0;
            finalAmount = Math.round((baseAmount - discount) * 100.0) / 100.0;
            attempts++;
        } while (busyAmounts.contains(finalAmount) && attempts < 50);

        if (finalAmount <= 0) {
            finalAmount = baseAmount;
            discountPaise = 0;
        }

        String upiToUse = (this.merchantUpiId != null && !this.merchantUpiId.isEmpty()) ? this.merchantUpiId : "";
        String orderId = "ORD" + System.currentTimeMillis();

        PaymentOrder order = new PaymentOrder();
        order.setOrderId(orderId);
        order.setAmount(finalAmount);
        order.setStatus("PENDING");
        order.setCustomerUtr("Not submitted");
        order.setCreatedAt(LocalDateTime.now());

        orderRepository.save(order);

        res.put("orderId", orderId);
        res.put("id", orderId);
        res.put("amount", finalAmount);
        res.put("originalAmount", baseAmount);
        res.put("discountPaise", discountPaise);
        res.put("upiId", upiToUse);
        res.put("status", "PENDING");
        return res;
    }

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

    @GetMapping("/api/admin/orders")
    public List<PaymentOrder> getAllOrders() {
        return orderRepository.findAll();
    }

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
        if (lower.contains("sent rs") || lower.contains("dr. from") || lower.contains("debited") || lower.contains("dr from")) {
            res.put("status", "IGNORED_DEBIT");
            return res;
        }

        Double amount = null;
        Pattern amtPat = Pattern.compile("(?:rs\\.?|inr|₹)\\s*([0-9]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
        Matcher amtMat = amtPat.matcher(message);
        if (amtMat.find()) {
            try {
                amount = Double.parseDouble(amtMat.group(1));
            } catch (Exception ignored) {}
        }

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

        if (amount != null) {
            List<PaymentOrder> pendingOrders = orderRepository.findByStatus("PENDING");
            LocalDateTime allowedTimeLimit = LocalDateTime.now().minusMinutes(25);

            for (int i = pendingOrders.size() - 1; i >= 0; i--) {
                PaymentOrder order = pendingOrders.get(i);
                if (Math.abs(order.getAmount() - amount) < 0.01) {
                    if (order.getCreatedAt() == null || order.getCreatedAt().isAfter(allowedTimeLimit)) {
                        order.setStatus("PAID SUCCESSFULLY");
                        if (utr != null && !utr.isEmpty()) {
                            order.setCustomerUtr(utr);
                        }
                        order.setVerifiedBy("SMS_AUTOMATION");
                        orderRepository.save(order);
                        res.put("status", "SUCCESS");
                        res.put("orderId", order.getOrderId());
                        res.put("utr", utr);
                        return res;
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
                        
