# Promotion system implementation

This document maps the research package to the production implementation introduced from
`docs/promotion-system-research`.

## Runtime components

| Area | Implementation |
| --- | --- |
| Deterministic pricing | `promotion/pricing/PricingEngine`, `AllocationEngine`, `MathSupport` |
| Campaigns and immutable rules | `PromotionService` |
| Platform/seller/shared funding | pricing applications plus checkout funding shares |
| Persistent cart and mixed checkout | `CartService`, `CheckoutQuoteService`, `CheckoutService` |
| Coupons | `CouponCatalogService`, `CouponService` |
| Memberships | `MembershipCatalogService`, `MembershipService`, `MembershipCodeService` |
| Seller promotions | `SellerPromotionService` |
| Recharge/recycle directional benefits | `DomainBenefitService` |
| Frozen partial refunds | `CommerceRefundService` and per-unit payment rows |
| HTTP APIs | `CommerceHttpApi` registered by `EmbeddedWebServer` |
| Database capability | `PromotionSchema` and `db/sqlite/schema.sql` |

## Pricing invariants

- Every monetary value uses signed 64-bit integer minor units and exact arithmetic.
- Rules execute in explicit layers against either original (`P0`) or current bases.
- Conflicting rules are optimized as combinations; disjoint scopes in the same slot may coexist.
- The winner is deterministic by payable amount, expiry, pinned selection, rule count and rule IDs.
- Discounts are allocated by largest remainder with line capacity and stable-ID tie breaking.
- Checkout stores the quote, applications, line allocations, funding shares and per-unit final amounts.
- Refunds consume unrefunded frozen units and never run the current promotion rules again.

## HTTP surface

User APIs:

- `GET /api/cart`
- `POST /api/cart/lines/add|update|remove`
- `POST /api/cart/clear`
- `POST /api/checkout/quote`
- `POST /api/checkout/submit`
- `POST /api/checkouts/refund`
- `GET /api/coupons/mine`
- `POST /api/coupons/claim`
- `GET /api/membership/me`
- `POST /api/membership/redeem`

Seller APIs:

- `GET /api/seller/promotions/list`
- `POST /api/seller/promotions/create`
- `POST /api/seller/promotions/action`

Administrator APIs:

- campaign list/create/publish/action and emergency stop under `/api/admin/promotions/*`
- coupon template creation and grants under `/api/admin/coupons/*`
- membership plan/version/product binding/code/grant/revoke under `/api/admin/membership/*`

All endpoints use the existing bearer session, admin role model and service error envelope. Cart,
quote, checkout, coupon consumption, membership redemption and refund mutations are idempotent or
optimistically versioned as specified by the API contract.

## Compatibility and rollout

Legacy official-product and market-purchase endpoints remain available. Unified checkout delegates
to transaction-inner legacy adapters after one parent-level wallet debit, preventing double debit
while preserving stock, delivery and market settlement behavior. The schema is additive and supports
both SQLite and MySQL/MariaDB. Campaign emergency stop is separately permissioned.

Frontend support is delivered in `Cc-Cece/webshopx-vuetify-web` on the matching branch. It adds the
mixed cart, best-combination explanation, coupon/membership center, seller promotion management and
administrator promotion/membership screens, with complete `en-US` and `zh-CN` resources.
