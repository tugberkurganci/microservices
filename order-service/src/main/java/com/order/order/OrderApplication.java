package com.order.order;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@EnableFeignClients
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }

}

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void placeOrder(@RequestBody OrderRequest orderRequest) {
        orderService.placeOrder(orderRequest);

    }
}

record OrderRequest(double quantity, int productId) {

}

@Service
@RequiredArgsConstructor
class OrderService {

    private final OrderRepository orderRepository;
    private final KafkaTemplate kafkaTemplate;
    private final InventoryClient inventoryClient;

    public void placeOrder(OrderRequest orderRequest) {


        InventoryStatus status = inventoryClient.exists(String.valueOf(orderRequest.productId()));
        if (!status.isExists()) {
            throw new EntityNotFoundException("Product does not exist");
        }

        Order o = orderRepository.save(Order.builder().productId(orderRequest.productId()).price(orderRequest.quantity()).status("PLACED").build());

        this.kafkaTemplate.send("prod.orders.placed", String.valueOf(o.getId()), OrderPlacedEvent.builder()
                .productId(orderRequest.productId())
                .orderId(o.getId())
                .build());

    }


    @KafkaListener(topics = "prod.orders.shipped", groupId = "order-group")
    public void handleOrderShippedEvent(String orderId) {
        this.orderRepository.findById(Integer.valueOf(orderId)).ifPresent(order -> {
            order.setStatus("SHIPPED");
            this.orderRepository.save(order);
        });
    }
}

@Repository
interface OrderRepository extends JpaRepository<Order, Integer> {
}

@Entity(name = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private int productId;
    private Double price;
    private String status;
}

@Data
@Builder
class OrderPlacedEvent {

    private int orderId;
    private int productId;
    private double price;
}

@FeignClient(url = "http://localhost:8080", name = "inventories")
interface InventoryClient {
    @GetMapping("/inventories")
    InventoryStatus exists(@RequestParam("productId") String productId);
}

@Data
class InventoryStatus {
    private boolean exists;
}