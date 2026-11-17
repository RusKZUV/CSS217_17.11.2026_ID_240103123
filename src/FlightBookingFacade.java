public class FlightBookingFacade {
    // Encapsulated subsystem references
    private final SeatInventorySystem seatSystem;
    private final BaggagePricingEngine baggageEngine;
    private final CurrencyConverter currencyConverter;
    private final PaymentProcessor paymentProcessor;
    private final TicketIssuer ticketIssuer;

    public FlightBookingFacade() {
        this.seatSystem = new SeatInventorySystem();
        this.baggageEngine = new BaggagePricingEngine();
        this.currencyConverter = new CurrencyConverter();
        this.paymentProcessor = new PaymentProcessor();
        this.ticketIssuer = new TicketIssuer();
    }

    /**
     * Single entry point for booking a complete flight itinerary.
     * Coordinates seat locking, baggage pricing, currency conversion,
     * payment execution, rollback on failure, and ticket issuance.
     */

    // 8-параметровая версия — оставлена для обратной совместимости.
    // Просто делегирует в 9-параметровую с уровнем лояльности "STANDARD".
    public String bookFlight(String passengerName,
                             String flightCode,
                             String seatNumber,
                             double baseFareUSD,
                             int bagCount,
                             double bagWeightKg,
                             String currency,
                             String cardNumber) {
        return bookFlight(passengerName, flightCode, seatNumber, baseFareUSD,
                bagCount, bagWeightKg, currency, cardNumber, "STANDARD");
    }

    // 9-параметровая перегрузка с обработкой уровня лояльности.
    // SILVER/GOLD — пропускаем движок багажа целиком (сбор = 0.0).
    // SILVER — дополнительно применяем промокод "SILVER-FREE-BAG" перед списанием.
    public String bookFlight(String passengerName,
                             String flightCode,
                             String seatNumber,
                             double baseFareUSD,
                             int bagCount,
                             double bagWeightKg,
                             String currency,
                             String cardNumber,
                             String loyaltyTier) {
        String lockToken = null;

        try {
            // Шаг 1: подключаемся к серверу мест и блокируем нужное место
            seatSystem.connectToReservationServer();
            lockToken = seatSystem.lockSeat(flightCode, seatNumber, passengerName);

            // Шаг 2: считаем сбор за багаж в USD (зависит от уровня лояльности)
            double totalUSD = baseFareUSD;
            boolean isPremiumTier = "SILVER".equalsIgnoreCase(loyaltyTier)
                    || "GOLD".equalsIgnoreCase(loyaltyTier);

            if (!isPremiumTier) {
                double baggageFeeUSD = baggageEngine.calculateBaggageFeeUSD(bagCount, bagWeightKg);
                totalUSD += baggageFeeUSD;
            } else {
                System.out.println("[Facade] Уровень лояльности '" + loyaltyTier
                        + "' - сбор за багаж отменён. BaggagePricingEngine пропущен.");
            }

            // Шаг 3: конвертируем итоговую сумму в валюту пассажира
            double convertedTotal = currencyConverter.convertFromUSD(totalUSD, currency);

            // Шаг 4: инициализируем платёжный шлюз
            paymentProcessor.initializeGateway();

            // Шаг 4b: для SILVER применяем промокод ДО списания денег
            if ("SILVER".equalsIgnoreCase(loyaltyTier)) {
                paymentProcessor.applyPromoCode("SILVER-FREE-BAG");
            }

            // Шаг 4c: списываем деньги с карты
            String receiptId = paymentProcessor.chargeCard(cardNumber, convertedTotal, currency);

            // Шаг 5: генерируем и возвращаем финальный код электронного билета
            return ticketIssuer.generateTicket(passengerName, flightCode, lockToken, receiptId);

        } catch (RuntimeException ex) {
            // Шаг 6: если место уже было заблокировано — освобождаем его перед сообщением об ошибке
            if (lockToken != null) {
                seatSystem.releaseSeatLock(lockToken);
            }
            System.out.println("[Facade] Бронирование не удалось: " + ex.getMessage());
            return "BOOKING_FAILED: " + ex.getMessage();
        } finally {
            // Шаг 7: соединение с сервером мест закрываем в любом случае —
            // и при успехе, и при ошибке
            seatSystem.disconnect();
        }
    }
}