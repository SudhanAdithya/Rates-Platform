# Rates Platform

## Project overview
Rates Platform is a rates and fixed-income analytics platform for tracking, analyzing, and modeling interest-rate products, market data, and portfolio exposures.

## Goals
- Provide a clean foundation for rate curve, swap, bond, and market data analysis
- Support scenario and risk analytics for rates desks
- Keep the codebase well-structured, testable, and easy to extend

## Recommended stack
- Backend: Python or TypeScript depending on implementation needs
- API: FastAPI or Express/NestJS
- Frontend: React or Next.js if a UI is required
- Data layer: PostgreSQL/TimescaleDB or a market-data store
- Analytics: pandas, NumPy, QuantLib, or similar tools as needed

## Working conventions
- Keep business logic separate from API and UI code
- Use clear naming for rates, curves, instruments, and risk metrics
- Prefer small, testable modules over large monolithic files
- Document assumptions around market conventions, day counts, and curve interpolation

## Development workflow
1. Set up a virtual environment or package manager for the project.
2. Install dependencies for both app and analytics layers.
3. Add tests for pricing, curve, and market-data logic.
4. Validate calculations against benchmark examples before merging.

## Notes
- Treat market data quality and conventions as critical.
- Validate all assumptions for compounding, holiday calendars, and curve interpolation.
- Keep risk calculations transparent and reproducible.
